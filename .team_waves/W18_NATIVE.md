# W18C — Wave 18 Report (native / watchdog / scoping patch design)

## Scope inspected
Read-only as-implemented: `cpp/wayland/compositor.c:440-485,700-771,3140-3229`, `cpp/wayland/deck_ahb_broker.inc:100-132`, `cpp/native_input.cpp:398-419,588-607`, `cpp/linux_android_usb.c:345-362`; `WatchdogDaemon.kt`, `WatchdogClient.kt`, `WatchdogProtocol.kt`, `WatchdogProtocolTest.kt`, `privilege/PublisherKeys.kt`; `MirrorService.kt:400-417,1055-1076,12185-12206,12715-12727,2839-2872,3066-3076`, `DeviceProfiles.kt`, `CapabilitySnapshot.kt`, `DeviceMatchTest.kt`. No source edits, no build, no token use.

## Findings

### P1 — gpu_job fence watch: unchecked add_fd leaks linked job; HUP-only spin (BUG-04 sibling)
`compositor.c:739` links the job and takes refs on images/dmabufs; `:745-746` ignores the `wl_event_loop_add_fd()` result. NULL source → no `gpu_job_ready`, completion only via the 16 ms `gpu_jobs_collect()` poll fallback. Worse, `gpu_job_ready()` at `:457-475` gates on `WL_EVENT_READABLE` alone and re-polls with `poll(...,0)`; a HUP/ERROR-only delivery (driver closes bmapped fence FD on GPU reset) returns 0 with the source still registered → level-triggered spin (exact BUG-04 pattern).

### P1 — Untracked privileged command threads
`MirrorService.kt:3071`: `Thread({ privilegedAccess.execute(*arguments) }, "DextopPlasmaCommand").start()` — non-daemon, not stored, not cancelled in `onDestroy()` (`:2839-2872`). Each Plasma/Deck command can spawn an unbounded shell-privileged thread that survives session teardown and keeps the privileged provider Binder alive; a 120 Hz producer can exhaust threads. Checked clean: `native_input.cpp:405,412-413` tracks/joins `worker_` under `lifecycleMutex_`; `compositor.c:3221-3227` tracks `comp->thread` + `pthread_detach`; `deck_ahb_broker.inc:129-130` detaches; `linux_android_usb.c:354-361` detaches and frees on failure. Only the Kotlin fire-and-forget privileged thread is untracked.

### P1 — Daemon authorization: non-constant-time token + environ fallback
`WatchdogDaemon.kt:62` compares the shared secret with Kotlin `==` (timing leak) while `PublisherKeys.matches()` at `PublisherKeys.kt:47` already uses `MessageDigest.isEqual`. `:34` also accepts `DEXTOP_WD_TOKEN` from the environment — readable by any same-UID process through `/proc/<pid>/environ` (BUG-05 "weak environ auth" sibling). `:55-101` reads exactly one frame per socket, so HELLO/ARM/ACK never complete (W17 finding); the frame loop must re-gate every frame on `authenticated`, otherwise a post-HELLO frame is trusted without a nonce bind.

### P1 — Manufacturer-wide scoping leftovers
- `DeviceProfiles.kt:85` `xiaomi_hyperos` matches *all* Xiaomi/Redmi/POCO and applies the full verified-on-one-device workaround set (W17-CAP finding).
- `CapabilitySnapshot.kt:133-135` `scopedDeviceProfile` returns supported for any `xiaomi_*`; `:186-193` `backend` recommends C/F for broad Xiaomi phones although runtime applies the direct-Plasma path; `:189-190` recommends A for Samsung DeX on API 36 (should be B).
- `MirrorService.kt:12193-12199,12725` force-freeform `Settings.Global` writes on every Xiaomi profile; `:1406-1407` disables physical-input routing for all Samsung; `:406-413` writes `sem_bottom_gesture_restored` on all Samsung restores.
- `CapabilitySnapshot.kt:83,85-87` `Class.forName(name)` runs static initializers, violating the "side-effect-free" contract.

## Deliverable (exact patches)

### Patch 1 — `cpp/wayland/compositor.c` (gpu_job)
Replace `:457-475` with:
```c
static int gpu_job_ready(int fd, uint32_t mask, void *data) {
    struct gpu_job *job = data;
    struct comp *comp = job->comp;          /* capture before gpu_job_finish() frees job */
    uint32_t window_id = job->window_id;
    bool done = false;
    if (mask & (WL_EVENT_HANGUP | WL_EVENT_ERROR)) {
        /* Driver closed the fence (GPU reset / bmap teardown): finish now,
         * otherwise the level-triggered HUP spins this source every loop tick. */
        done = true;
    } else if (mask & WL_EVENT_READABLE) {
        struct pollfd complete = {.fd = fd, .events = POLLIN};
        done = poll(&complete, 1, 0) == 1 && (complete.revents & POLLIN);
    }
    if (!done) return 0;
    gpu_job_finish(job);                    /* removes source, closes fence, unrefs */
    struct xdg *xdg = xdg_by_id(comp, window_id);
    if (xdg && xdg->deck && xdg->deck_no_vsync && xdg->mapped && xdg->window && xdg->needs_redraw) {
        xdg->needs_redraw = false;
        window_draw(xdg);                   /* forward decl already at :455 */
    }
    return 0;
}
```
Replace `:745-746` with:
```c
        if (job->fence >= 0) {
            job->source = wl_event_loop_add_fd(xdg->comp->loop, job->fence,
                WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR, gpu_job_ready, job);
            if (!job->source) {
                LOGW("GPU fence watch failed window=%u fence=%d; releasing job",
                     xdg->window_id, job->fence);
                /* job is already linked (:739) and holds image/buffer refs;
                 * the normal finish path removes it from the list and frees it. */
                gpu_job_finish(job);
            }
        }
```
`gpu_job_finish()` already null-checks `job->source`/`job->fence` (`:445-446`), so this is leak-free. No later statement touches `job`.

### Patch 2 — `WatchdogDaemon.kt` (auth hardening + frame loop)
Imports: add `import java.security.MessageDigest`.
Replace `:34-36` with:
```kotlin
        // Token arrives only on the app's stdin pipe. Never accept it from the
        // environment: /proc/<pid>/environ is readable by same-UID processes.
        val token = System.`in`.bufferedReader().use { it.readLine() } ?: return
```
Replace `handle()` (`:55-101`) with:
```kotlin
    private fun handle(client: LocalSocket, appUid: Int, token: String) {
        val expected = token.toByteArray(Charsets.UTF_8)
        var authenticated = false
        var helloNonce: String? = null
        try {
            while (true) {
                val message = try {
                    WatchdogProtocol.read(client.inputStream)
                } catch (error: IOException) {
                    break // EOF/CRC failure: close this connection, keep serving
                }
                when (message.optString("type")) {
                    "HELLO" -> {
                        if (authenticated) break // one HELLO per connection
                        val presented = message.optString("token").toByteArray(Charsets.UTF_8)
                        if (peerUidOk(client, appUid) &&
                            MessageDigest.isEqual(expected, presented)) { // constant-time
                            helloNonce = message.optString("nonce")
                            authenticated = true
                            WatchdogProtocol.write(client.outputStream,
                                WatchdogProtocol.envelope("HELLO_ACK").put("nonce", helloNonce))
                        }
                    }
                    "ARM" -> if (authenticated && message.optString("nonce") == helloNonce) {
                        val seq = message.optLong("armSeq", -1L)
                        val sid = message.optString("sessionId")
                        if (seq >= armSeq && !hasReceipt(sid)) {
                            val actions = WatchdogProtocol.actionsFromJson(message.optJSONArray("actions"))
                            actions.forEach { WatchdogProtocol.validate(it.argv) }
                            armSeq = seq; sessionId = sid; snapshot = actions
                            watchedPid = message.optInt("pid", -1)
                            watchedStartTicks = message.optLong("pidStartTicks", -1L)
                            deadlineMs = System.currentTimeMillis() +
                                message.optLong("deadlineMs", DEFAULT_DEADLINE_MS)
                            dead.set(!isAlive(watchedPid, watchedStartTicks))
                            armed = true
                        }
                    }
                    "HEARTBEAT" -> if (authenticated && message.optLong("armSeq", -1L) == armSeq) {
                        deadlineMs = System.currentTimeMillis() + HEARTBEAT_WINDOW_MS
                    }
                    "ACK" -> if (authenticated && message.optString("sessionId") == sessionId) {
                        writeReceipt(sessionId); armed = false
                        WatchdogProtocol.write(client.outputStream, WatchdogProtocol.envelope("ACKED"))
                        exitProcess(0)
                    }
                }
            }
        } catch (error: Throwable) {
            // Malformed/unauthorized frame: connection closed by caller, keep serving.
        }
    }

    private fun peerUidOk(client: LocalSocket, appUid: Int) =
        runCatching { client.peerCredentials.uid == appUid }.getOrDefault(false)
```
`WatchdogClient.kt` ARM send (`:76-84`) add nonce binding:
```kotlin
            WatchdogProtocol.envelope("ARM")
                .put("nonce", nonce)          // bind ARM to this HELLO's connection
```
`WatchdogClient.kt:31` keep piping the token via `privileged.executeWithInput(token, ...)` (stdin); do NOT export `DEXTOP_WD_TOKEN`.

### Patch 3 — `MirrorService.kt` (untracked privileged threads)
Add fields near `:1325`:
```kotlin
    private val privilegedCommandExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DextopPlasmaCommand").apply { isDaemon = true }
    }
```
Replace `:3070-3073`:
```kotlin
        override fun privilegedExecute(vararg arguments: String): Boolean {
            val generation = serviceDestroyed
            privilegedCommandExecutor.execute {
                if (!generation) runCatching { privilegedAccess.execute(*arguments) }
            }
            return true
        }
```
(`serviceDestroyed` at `:2840` is the existing teardown flag; use `!serviceDestroyed` inline if capturing a val is awkward.) Add to `onDestroy()` after `:2867`:
```kotlin
        privilegedCommandExecutor.shutdownNow()
```

### Patch 4 — scoping leftovers
`DeviceProfiles.kt:85` — narrow the broad rule:
```kotlin
        DesktopEnvironmentRule(
            "xiaomi_hyperos",
            DeviceMatch(manufacturers = setOf("xiaomi", "redmi", "poco"), minSdk = 36)
        ) { identity ->
            DesktopEnvironmentRegistry.aospFreeform(identity)  // minimal fallback, no verified workarounds
        },
```
Keep the full `xiaomiHyperos(identity)` workarounds only in the narrow `xiaomi_redmi_pad_pro_5g` rule (`:69-84`) and add tested models (e.g. `aurora` / `24030PN60G`) there.
`CapabilitySnapshot.kt`:
```kotlin
    private fun classExists(name: String) =
        runCatching { Class.forName(name, false, CapabilitySnapshotEngine::class.java.classLoader); true }.getOrDefault(false)
    private fun methodExists(className: String, method: String) = runCatching {
        Class.forName(className, false, CapabilitySnapshotEngine::class.java.classLoader)
            .methods.any { it.name == method }
    }.getOrDefault(false)
```
```kotlin
        windowing["scopedDeviceProfile"] =
            if (matchedRule == "xiaomi_redmi_pad_pro_5g") Probe.yes(matchedRule)
            else Probe.no("generic_profile")
```
```kotlin
        val backend = when {
            environment.startupMode == DesktopStartupMode.OEM_MANAGED -> BackendId.B // DeX on API 36
            matchedRule == "xiaomi_redmi_pad_pro_5g" -> BackendId.E
            Build.VERSION.SDK_INT >= 36 && environment.platformManaged -> BackendId.A
            display["trustedVirtualDisplay"]?.supported == true -> BackendId.C
            else -> BackendId.F
        }
```
`MirrorService.kt`: gate global writes on the narrow rule at `:12193`/`:12725` (`isScopedXiaomiTablet` instead of `isXiaomiProfile` for `forceFreeform`), make `physicalInputRoutingSupported` (`:1406`) probe-based instead of `!Build.MANUFACTURER.equals("samsung")`, and record the `sem_bottom_gesture_restored` mutation in `SessionJournal` so `:406-413` only restores when it was actually set.

## CI verification (GitHub Actions only, no local builds)
- `./gradlew :app:testDebugUnitTest` — extend `WatchdogProtocolTest` with `daemon_loop_requires_hello_then_arm_then_ack_over_one_socket` driving HELLO→ARM→HEARTBEAT→ACK on one `LocalSocket` (assert rollback receipts written), `bad_token_rejected_in_constant_time`, and `arm_without_matching_nonce_ignored`. Extend `DeviceMatchTest` with `generic_xiaomi_phone_gets_minimal_profile` / `scoped_tablet_keeps_full_workarounds`.
- Workflow grep guards (fail on regression):
  - `grep -q 'MessageDigest.isEqual(expected, presented)' WatchdogDaemon.kt`
  - `! grep -q 'DEXTOP_WD_TOKEN' WatchdogDaemon.kt`
  - `grep -q 'WL_EVENT_READABLE | WL_EVENT_HANGUP | WL_EVENT_ERROR' compositor.c` and `grep -q 'gpu_job_finish(job);' compositor.c`
  - `! grep -q 'isXiaomiProfile && xiaomiFlags.forceFreeform' MirrorService.kt`
- Native job: CMake `ndk-build` with `-Wall -Wextra -Werror=unused-result` and `clang-tidy` `bugprone-unchecked-optional-access` on `compositor.c`; assert zero warnings from the patched lines.
- Static analyzer: `./gradlew :app:lintDebug` for the new executor field (detect leaked `ExecutorService`).

## Risks / device testing needed
- GPU reset test on Redmi Pad Pro 5G: kill Turnip mid-submit, confirm no HUP spin and `gpu_releases` counter (compositor.c:3030-3057) increments; check no leaked `linux_gpu_image`/dmabuf refs.
- Watchdog end-to-end: kill app while ARMed, verify 15 s rollback receipts in `/data/local/tmp/dextop/watchdog`; confirm token rotation (`PublisherKeys.issue`) re-auths on reconnect.
- Scoping: capture real Deck launcher + Xiaomi phone/tablet fingerprints on device before removing broad workarounds; keep a versioned adapter if the launcher still relies on `DEXTOP_STEAM_INSIDE=1` or manufacturer-wide freeform.
