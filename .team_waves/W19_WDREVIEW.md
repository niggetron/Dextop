# Watchdog Integration Review — Wave 19

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogDaemon.kt` (159 lines)
- `.../WatchdogClient.kt` (103), `.../WatchdogProtocol.kt` (113), `.../SessionJournal.kt` (448)
- Supporting: `PrivilegedAccess.kt`, `privilege/PublisherKeys.kt`, `privilege/DistributionPrivilegeRuntime.kt`, `DisplayMirrorBackend.kt`
- Verified by grep: no production caller of `WatchdogClient.heartbeat()`; only `arm()` (SessionJournal:130) and `ack()` (:213) are invoked.
- No builds, no edits, no token used.

## Findings

### P0-1 — Daemon reads ONE frame per socket then closes; client keeps one socket for the whole session
- `WatchdogDaemon.kt:44-52` (`accept()` → `handle()` → `client.close()`), `WatchdogDaemon.kt:55-101` (`handle()` calls `read()` exactly once).
- `WatchdogClient.kt:45-53` (`connect()` sends HELLO and stores that socket), `WatchdogClient.kt:56-67` (`send()` reuses it for ARM/HEARTBEAT/ACK).
- Why it fails on device: the only frame the daemon ever processes is HELLO (sets `authenticated`, replies HELLO_ACK, then closes at :51). Every subsequent ARM/HEARTBEAT/ACK is written to a peer-closed socket. **ARM is never processed, `armed` never becomes true, `deadlineMs` stays `Long.MAX_VALUE` — the watchdog never rolls back anything.** On reconnect (`WatchdogClient.kt:64`) the second `write()` at :65 is outside any try/catch, so it throws and is silently swallowed by `SessionJournal.armWatchdog()` (:130-131). No amount of heartbeat or WAL correctness matters until this is fixed.
- Fix: make `handle()` a read loop over the same connection:
  ```
  while (true) { val msg = try { read(input) } catch (e: EOFException) { break }
                 when (msg.optString("type")) { "HELLO" -> {authenticated=...; write HELLO_ACK}
                   "ARM"/"HEARTBEAT"/"ACK" -> if (authenticated) ... } }
  ```
  Remove the per-message `client.close()` (close only on EOF/error). Keep client's single-socket model. Add test `daemon_loop_requires_hello_then_arm_then_ack_over_one_socket`.

### P0-2 — `heartbeat()` is dead code; 15 s wall-clock deadline always fires on a healthy session
- `WatchdogClient.kt:87-90` (defined, never called), `WatchdogDaemon.kt:17-18, 82-83, 88-89, 110`.
- Why it fails on device: deadline is set only at ARM and extended only by HEARTBEAT; the supervisor fires when `System.currentTimeMillis() > deadlineMs` regardless of app liveness (`:110` is `dead || timeout`). With no heartbeat scheduled, **every session is rolled back ~15 s after the first mutation**, reverting settings/overlay/wm while the user is still in the desktop. HyperOS sessions routinely exceed 15 s. (Once P0-1 is fixed this becomes the dominant failure.)
- Fix: schedule `watchdog.heartbeat()` every 5 s from a session-scoped `CoroutineScope(Dispatchers.IO)` created in `SessionJournal`, cancel it in `restoreAll()`/`clear()`/teardown. Use `SystemClock.elapsedRealtime()` for both deadline and heartbeat window so NTP/user clock changes can't move the deadline.

### P0-3 — Liveness check fails closed (parse/read error == "process dead" == rollback)
- `WatchdogDaemon.kt:109, 121-128`; producer `WatchdogClient.kt:73-81`.
- Why it fails on device: `isAlive()` returns `false` for unreadable `/proc/<pid>/stat`, CRC/format drift, or any `toLongOrNull()` failure; `ARM` immediately sets `dead.set(!isAlive(...))` (`:84`) and the supervisor fires within 100 ms. Hardened `/proc` + HyperOS SELinux can deny the shell daemon reads of the app's stat. A transient parse miss thus **rolls back a live app instantly**.
- Fix: tri-state result (`ALIVE` / `DEAD` / `UNKNOWN`). Only set `dead=true` on a confirmed start-tick *mismatch for the same PID*. On read/parse failure set `UNKNOWN` and do NOT fire (keep deadline as the sole fallback).
- Note: the starttime index itself is CORRECT — `getOrNull(19)` after the final `')'` is `/proc` field 22 (verified empirically). `substringAfterLast(')')` also correctly handles `comm` containing `)`. **Do not "fix" the index.**

### P1-4 — Token leaks through the environment on the Shizuku privilege path
- `PrivilegedAccess.kt:50-53` (`newProcess(arguments, arrayOf("DEXTOP_WD_TOKEN=$input"), null)`), `WatchdogDaemon.kt:34-36`.
- Why it matters: the watchdog runs as shell; any other shell-UID process on the tablet can read `/proc/<wdPid>/environ`, recover the token, then HELLO-authenticate and either ACK (suppress rollback) or inject ARM with attacker argv. Same class as BUG-05.
- Fix: pass the token through the third `fds` argument of `newProcess` (a pipe) or a 0400 FD, and read it from that FD in the daemon; delete the env-var branch. Keep `==` but use `MessageDigest.isEqual` on the token compare.

### P1-5 — `start()` ignores launch failure and races the abstract-socket bind
- `WatchdogClient.kt:36-37` (return value of `executeWithInput` discarded, `connect()` immediately), `WatchdogDaemon.kt:37-41` (bind failure → silent `return`).
- Why it matters: if Shizuku/embedded runtime is unavailable or HyperOS kills the detached `app_process`, no daemon exists; or `connect()` wins the race before `LocalServerSocket` binds. Both throw out of `connect()` and are swallowed at `SessionJournal.kt:130`. Result: no watchdog, no signal.
- Fix: check `result.succeeded`; retry `connect()` with bounded backoff (e.g. 10×100 ms) before giving up; log/propagate a real failure instead of silent `runCatching`.

### P1-6 — ACK is best-effort and early-returns while disconnected; daemon trusts ACK blindly
- `WatchdogClient.kt:92-96` (`if (!connected) return`), `WatchdogDaemon.kt:91-96` (write receipt → `armed=false` → reply → `exitProcess(0)`), `SessionJournal.kt:211-215`.
- Why it matters: after restore, if the socket dropped, `ack()` silently does nothing → no receipt → daemon still fires at deadline and re-applies rollback **after** the app already restored (double rollback). Conversely the daemon exits on any ACK with a matching sessionId without verifying restore completeness.
- Fix: remove the early return in `ack()` and always go through `send()` (which reconnects). Have the daemon write+`fsync` the receipt before replying ACKED, and only then exit. Treat ACK failure as a restore failure and keep the WAL open.

### P2-7 — `SAFE_VALUE` drops legitimate restore values, silently shrinking the snapshot
- `WatchdogProtocol.kt:18-19, 85, 92, 97`; value producer `DisplayMirrorBackend.kt:112, 131` (`joinToString(";")`).
- Why it matters: `overlay_display_devices` can be `"WxH/dens,flags;WxH/dens"` in multi-display (phone+Auto / preserved overlay) sessions. `BAD` includes `;` and `SAFE_VALUE` excludes it, so `validate()` throws and `SessionJournal.watchdogActions():120-122` silently skips the overlay restore action. Empty-string before-values also fail (`+` requires ≥1 char). Rollback then leaves the overlay display requested. Real risk on the 12.1" tablet's multi-display backends.
- Fix: the daemon never invokes a shell, so shell-metachar filtering is unnecessary — validate argv shape only; allow `;` (and empty string) in values, or base64-encode values in `RestoreAction` and decode in `fireAndExit()`.

### P2-8 — Wall-clock timeouts
- `WatchdogDaemon.kt:82-83, 89, 110` use `System.currentTimeMillis()`.
- Why: NTP correction / user changing date during a session shifts the deadline; can fire early or never. Fix: `android.os.SystemClock.elapsedRealtime()` throughout (available under `app_process`).

### P2-9 — No binding of HELLO nonce to subsequent messages; stale/duplicate ARM accepted
- `WatchdogDaemon.kt:60-70` (nonce echoed but never stored), `:71-87` (`seq >= armSeq` accepts equal/stale, no nonce check), `WatchdogClient.kt:69-84`.
- Why: any authenticated connection can overwrite the snapshot with a lower-or-equal seq, and a replayed HELLO can be used for a fresh connection. Fix: store the connection nonce; require it on ARM/HEARTBEAT/ACK and require `seq > armSeq` for a new snapshot (equal only for an exact re-arm of the same session).

### P3-10 — Post-fire race leaves a zombie daemon
- `WatchdogDaemon.kt:88, 110-117, 130-143`: a late ARM after `fired=true` can set `armed=true` again; supervisor keeps spinning but `fireAndExit()` CAS-returns, so the process never exits and keeps the `@dextop.wd.<uid>` name.
- Fix: reject ARM/HEARTBEAT when `fired.get()` is true.

## Deliverable (code sketch for the critical fix)
`WatchdogDaemon.kt` — replace single-shot handle loop:
```kotlin
private fun handle(client: LocalSocket, appUid: Int, token: String) {
    var authenticated = false
    while (true) {
        val message = try { WatchdogProtocol.read(client.inputStream) }
                       catch (e: IOException) { break }        // peer EOF
        if (fired.get()) break
        when (message.optString("type")) {
            "HELLO" -> { /* existing peerUid/token check; write HELLO_ACK */ }
            "ARM"          -> if (authenticated) { /* existing, plus seq > armSeq */ }
            "HEARTBEAT"    -> if (authenticated && message.optLong("armSeq",-1)==armSeq) { /* elapsedRealtime */ }
            "ACK"          -> if (authenticated && ...) { writeReceipt(); fsync; reply ACKED; exitProcess(0) }
        }
    }
}
```
Then delete `client.close()` at `:51` (close on loop exit). Pair with a 5 s heartbeat scheduled from `SessionJournal` and a tri-state `isAlive()`.

## Risks / device testing needed
- Cannot validate SELinux/HyperOS policy for abstract sockets or shell reads of `/proc/<appPid>/stat` without the Redmi Pad Pro 5G; P0-3 tri-state is mandatory regardless.
- Verify `settings`/`wm` shell binaries accept the generated argv order on HyperOS 3; `wm size reset -d <id>` parsing is AOSP-order-independent but must be smoke-tested on the virtual-display backend.
- Verify `/data/local/tmp/dextop/watchdog` receipt dir is writable by the shell daemon and that receipts survive app restarts without colliding.
- After fixes, device-test: (a) ARM→kill app→settings reverted within 15 s; (b) healthy 60 s session with heartbeat→no rollback; (c) ACK→no rollback; (d) overlay multi-display rollback; (e) token not present in `/proc/<wdPid>/environ`.
