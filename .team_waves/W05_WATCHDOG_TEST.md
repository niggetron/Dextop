# W5B (M2) — Wave 05 Report: Watchdog Test Design

## Scope inspected
- `.team_waves/W04_WATCHDOG.md` (detached shell daemon decision; wire format; integration points; 9-step kill-9 sketch)
- `.team_waves/W04_FSM.md` (generation-carried effects, uncancellable `postDelayed` P1, 15 s watchdog P2)
- `SessionJournal.kt:222-238,240-259,261-280,296-325,361-381` (WAL frame `{seq,op,ts,raw,crc}`, `restoreAll()` reverse loop :143, `SessionWalCodec`)
- `DesktopSessionFsm.kt:25-64,104-194` (current additive reducer; no watchdog effects); `DesktopSessionFsmTest.kt:1-65`; `SessionWalCodecTest.kt:1-54`
- `.agents/skills/dextop-privilege-wal-guard/SKILL.md:8-15` (rules 1/3/4/5); `MirrorService.kt:12030-12066,13868-14023,2820-2839`

## Findings
- **P0 — no watchdog tests exist.** `WatchdogProtocolTest.kt` / `WatchdogKillTest.kt` named in `W04_WATCHDOG.md:38` are absent; only `SessionWalCodecTest.kt` covers CRC in isolation. A framing/CRC/allowlist/order regression silently breaks kill-9 rollback (skill rule 5). Fix: §A + §D.
- **P0 — 15 s confirmation loop is not in the FSM.** `DesktopSessionFsm.kt:38-64` has no `ScheduleWatchdog`/`UserConfirmed`/`WatchdogExpired`; `MirrorService.kt` owns bare `postDelayed` chains (`W04_FSM.md:12`). Fix: §B (FSM emits effects; MirrorService owns one generation-keyed cancellable dispatcher).
- **P1 — clean teardown does not ACK the detached daemon.** `SessionJournal.kt:154,198` ACK on commit/clear, but `MirrorService.onDestroy:2839`/`onUnbind:2820` cancel no watchdog timer and issue no explicit ACK → a clean stop can still see a late rollback after journal rewrite. Fix: `cancelAllWatchdogsOnTeardown(gen)` in §B.
- **P2 — allowlist is implicit.** `validate(argv)` not centralized/tested; metachar injection via a forged `ARM` would run `settings put global evil_key ...`. Fix: pinned key set + regex + metachar rejection in §A.
- **P3 — JVM seam is ready.** `testImplementation(kotlin("test"))` + `org.json` present (`W04_FSM.md:15`); keep `WatchdogProtocol`/FSM Android-free so tests run on JVM in CI.

## Deliverable

### A. JVM unit tests — exact Kotlin

API under test (`WatchdogProtocol.kt`, pure JVM, consistent with `W04_WATCHDOG.md:29-33`). Frame layout: `MAGIC("DW01",4) | LEN_LE(4) | UTF8_PAYLOAD(N) | CRC32_LE(payload,4)`.

```kotlin
// android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogProtocol.kt (contract)
internal data class RestoreAction(
    val kind: String,                 // setting | wm | overlay | wallpaper
    val argv: List<String>,           // e.g. ["settings","put","global","enable_freeform_support","1"]
    val handledByApp: Boolean = false,
)
internal object WatchdogProtocol {
    const val MAGIC = "DW01"
    const val MAX_PAYLOAD = 256 * 1024
    val ALLOWED_KEYS = setOf(         // the 7 BUG-01 modernKeys + overlay spec
        "override_desktop_experience_features", "override_desktop_mode_features",
        "enable_non_resizable_multi_window", "force_desktop_mode_on_secondary_displays",
        "force_desktop_mode_on_external_displays", "force_resizable_activities",
        "enable_freeform_support", "DISPLAY_SPECIFICATION",
    )
    fun encode(json: String): ByteArray                       // require(size<=MAX); throws IAE
    fun decode(frame: ByteArray): String                       // throws IAE on magic/len/crc/truncation
    fun crc32(payload: ByteArray): Long
    fun validate(argv: List<String>): Boolean
    fun nonce(): String                                        // 32-char hex (16 random bytes)
    fun rollbackPlan(actions: List<RestoreAction>): List<RestoreAction> =
        actions.asReversed().filter { !it.handledByApp }       // last-write-first; skip app-owned
}
```

```kotlin
// android/app/src/test/kotlin/moe/n4tsu/dextop/WatchdogProtocolTest.kt
package moe.n4tsu.dextop

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pure JVM tests for the detached-watchdog wire protocol (M2 / skill rule 5). */
class WatchdogProtocolTest {
    private fun obj(type: String) = JSONObject().put("type", type)
    private fun le(bytes: ByteArray, off: Int) =
        ByteBuffer.wrap(bytes, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    @Test fun frame_roundtrip_preserves_payload() {
        val msg = obj("ARM").put("sessionId", "s-1").put("deadlineMs", 15_000)
        val decoded = JSONObject(WatchdogProtocol.decode(WatchdogProtocol.encode(msg.toString())))
        assertEquals("ARM", decoded.getString("type"))
        assertEquals("s-1", decoded.getString("sessionId"))
        assertEquals(15_000, decoded.getInt("deadlineMs"))
    }

    @Test fun frame_layout_is_magic_len_payload_crc_le() {
        val payload = obj("HELLO").put("nonce", "abc").toString().toByteArray(Charsets.UTF_8)
        val frame = WatchdogProtocol.encode(String(payload, Charsets.UTF_8))
        assertEquals("DW01", String(frame.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(payload.size, le(frame, 4))
        assertEquals(WatchdogProtocol.crc32(payload).toInt(), le(frame, 8 + payload.size))
        assertEquals(12 + payload.size, frame.size)
    }

    @Test fun decode_rejects_bad_magic() {
        val frame = WatchdogProtocol.encode(obj("HEARTBEAT").toString()).clone()
        frame[0] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { WatchdogProtocol.decode(frame) }
    }

    @Test fun decode_rejects_torn_payload_crc_mismatch() {
        val frame = WatchdogProtocol.encode(obj("ARM").put("armSeq", 7).toString()).clone()
        frame[10] = (frame[10].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalArgumentException> { WatchdogProtocol.decode(frame) }
    }

    @Test fun decode_rejects_truncated_frame() {
        val frame = WatchdogProtocol.encode(obj("ACK").toString())
        assertFailsWith<IllegalArgumentException> { WatchdogProtocol.decode(frame.copyOf(frame.size - 1)) }
        assertFailsWith<IllegalArgumentException> { WatchdogProtocol.decode(frame.copyOf(11)) }
    }

    @Test fun encode_rejects_oversized_payload() {
        assertFailsWith<IllegalArgumentException> {
            WatchdogProtocol.encode("x".repeat(WatchdogProtocol.MAX_PAYLOAD + 1))
        }
    }

    @Test fun allowlist_accepts_settings_put_delete_and_wm_reset() {
        assertTrue(WatchdogProtocol.validate(listOf("settings", "put", "global", "enable_freeform_support", "1")))
        assertTrue(WatchdogProtocol.validate(listOf("settings", "delete", "system", "force_resizable_activities")))
        assertTrue(WatchdogProtocol.validate(listOf("wm", "size", "reset", "-d", "0")))
        assertTrue(WatchdogProtocol.validate(listOf("wm", "density", "320", "-d", "12")))
    }

    @Test fun allowlist_rejects_shell_metacharacters() {
        val injections = listOf("1; reboot", "1 && id", "1\nosk", "\$(id)", "`id`", "1|sh", "1> /dev/x", "1' OR '1")
        injections.forEach { bad ->
            assertFalse(WatchdogProtocol.validate(listOf("settings", "put", "global", "enable_freeform_support", bad)), bad)
            assertFalse(WatchdogProtocol.validate(listOf("wm", "size", bad, "-d", "0")), bad)
        }
    }

    @Test fun allowlist_rejects_unknown_binary_key_and_shape() {
        assertFalse(WatchdogProtocol.validate(listOf("sh", "-c", "id")))
        assertFalse(WatchdogProtocol.validate(emptyList()))
        assertFalse(WatchdogProtocol.validate(listOf("settings", "put", "global", "evil_key", "1")))   // not in ALLOWED_KEYS
        assertFalse(WatchdogProtocol.validate(listOf("settings", "put", "global", "force-desktop-mode", "1"))) // regex
        assertFalse(WatchdogProtocol.validate(listOf("settings", "put", "secure", "enable_freeform_support", "1"))) // namespace
        assertFalse(WatchdogProtocol.validate(listOf("wm", "size", "reset")))                         // bad arity
    }

    @Test fun rollback_is_reverse_order_and_skips_app_owned_actions() {
        val k1 = RestoreAction("setting", listOf("settings", "put", "global", "enable_freeform_support", "1"))
        val k2 = RestoreAction("setting", listOf("settings", "put", "global", "override_desktop_mode_features", "1"), handledByApp = true)
        val wm = RestoreAction("wm", listOf("wm", "size", "reset", "-d", "0"))
        assertEquals(listOf(wm, k1), WatchdogProtocol.rollbackPlan(listOf(k1, k2, wm)))
    }

    @Test fun nonce_is_random_hex_32_chars() {
        val a = WatchdogProtocol.nonce()
        val b = WatchdogProtocol.nonce()
        assertEquals(32, a.length)
        assertTrue(a.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(a, b)
    }
}
```

### B. FSM emits `ScheduleWatchdog(15s, generation)`; MirrorService cancels on teardown

Two complementary timers — keep them distinct to avoid double rollback:
1. **Detached daemon** (survives `kill -9`): armed by `SessionJournal.append` per `W04_WATCHDOG.md:41-44`, ACK on commit/confirm.
2. **In-process FSM timer** (UX/rollback orchestration while alive): `ScheduleWatchdog` effect below, cancelled on clean teardown; vanishes on `kill -9`, leaving the daemon as the backstop.

```kotlin
// DesktopSessionFsm.kt — additive changes (matches current source API)
internal data class SessionCtx(val config: SessionConfig, val generation: Long,
                               val displayId: Int = -1, val sessionId: String = "") // add sessionId
internal sealed interface FsmState {
    // ...existing...
    data class AwaitingConfirmation(val ctx: SessionCtx) : FsmState // settings mutated; 15s user confirm
    data class RollingBack(val ctx: SessionCtx) : FsmState
}
internal sealed interface FsmEvent {
    // ...existing...
    data class UserConfirmed(val generation: Long) : FsmEvent
    data class WatchdogExpired(val generation: Long) : FsmEvent
}
internal sealed interface FsmEffect {
    // ...existing...
    data class ScheduleWatchdog(val delayMs: Long, val generation: Long, val sessionId: String) : FsmEffect
    data class CancelWatchdog(val generation: Long) : FsmEffect
}
private fun generationOf(s: FsmState): Long = when (s) { /* ...existing... */
    is FsmState.AwaitingConfirmation -> s.ctx.generation
    is FsmState.RollingBack          -> s.ctx.generation
}

// in StartRequested branch: SessionCtx(event.config, ++nextGeneration, sessionId = UUID.randomUUID().toString())
// in reduce(state,event) when-block:
is FsmEvent.DisplayAttached -> when (state) {
    is FsmState.Preparing, is FsmState.CreatingDisplay -> if (event.generation == generationOf(state)) {
        val attached = state.ctx.copy(displayId = event.displayId)
        val core = listOf(FsmEffect.ConfigureDisplay(event.displayId, attached.generation),
                          FsmEffect.MarkRunning(attached))
        if (attached.config.forceFreeform || attached.config.decorations ||
            attached.config.autoOnly) // any branch that mutates settings
            Transition(FsmState.AwaitingConfirmation(attached), core + FsmEffect.ScheduleWatchdog(
                15_000L, attached.generation, attached.sessionId))
        else Transition(FsmState.Running(attached), core)
    } else Transition(state, listOf(FsmEffect.Log("stale attach ${event.generation}")))
    else -> Transition(state)
}
is FsmEvent.UserConfirmed -> when (state) {
    is FsmState.AwaitingConfirmation -> if (event.generation == state.ctx.generation)
        Transition(FsmState.Running(state.ctx), listOf(FsmEffect.CancelWatchdog(state.ctx.generation)))
    else Transition(state) else -> Transition(state)
}
is FsmEvent.WatchdogExpired -> when (state) {
    is FsmState.AwaitingConfirmation -> if (event.generation == state.ctx.generation)
        Transition(FsmState.RollingBack(state.ctx), listOf(
            FsmEffect.CancelWatchdog(state.ctx.generation),
            FsmEffect.RestoreJournal(state.ctx.generation))) // journal kept open until verified
    else Transition(state) else -> Transition(state)
}
is FsmEvent.RestoreCompleted -> when (state) { /* ...existing Stopping/Recovering... */
    is FsmState.RollingBack -> Transition(FsmState.Failed(state.ctx, "watchdog rollback"),
        listOf(FsmEffect.ClearJournal(event.generation)))
    else -> Transition(state)
}
```

```kotlin
// MirrorService.kt — one generation-keyed cancellable dispatcher; all effects run on main
private val wdHandler = Handler(Looper.getMainLooper())
private val wdCallbacks = mutableMapOf<Long, Runnable>()

private fun runEffect(fx: FsmEffect) = when (fx) { /* ...existing branches... */
    is FsmEffect.ScheduleWatchdog -> {
        cancelWatchdog(fx.generation)                 // idempotent re-arm
        val r = Runnable {
            if (wdCallbacks.remove(fx.generation) == null) return@Runnable
            emit(FsmEvent.WatchdogExpired(fx.generation))
        }
        wdCallbacks[fx.generation] = r
        wdHandler.postDelayed(r, fx.delayMs)          // 15_000L, generation carried
    }
    is FsmEffect.CancelWatchdog -> cancelWatchdog(fx.generation)
    is FsmEffect.RestoreJournal -> restoreAllForSession(fx.generation) { ok ->
        emit(FsmEvent.RestoreCompleted(fx.generation, ok)) }
    is FsmEffect.ClearJournal -> sessionJournal.clear()   // -> watchdog ack (W04_WATCHDOG:44)
    else -> Unit
}
private fun cancelWatchdog(gen: Long) { wdCallbacks.remove(gen)?.let(wdHandler::removeCallbacks) }
private fun cancelAllWatchdogsOnTeardown(gen: Long) {
    wdCallbacks.values.forEach(wdHandler::removeCallbacks); wdCallbacks.clear()
    watchdogClient.ack(sessionJournal.snapshot()["sessionId"] as? String ?: gen.toString())
    // ack writes durable receipt -> detached daemon exits, no late rollback on clean stop
}

private fun emit(e: FsmEvent) {
    if (Looper.myLooper() == Looper.getMainLooper()) fsm.send(e).forEach(::runEffect)
    else wdHandler.post { fsm.send(e).forEach(::runEffect) }
}
// lifecycle: StopRequested terminal, onUnbind(:2820), onDestroy(:2839) -> cancelAllWatchdogsOnTeardown(fsm.generation)
// every async callback: emit(FsmEvent.WatchdogExpired(gen)) / UserConfirmed(gen) only where gen == fsm.generation
```

### C. FSM effect unit tests (JVM) — `WatchdogFsmEffectTest.kt`

```kotlin
class WatchdogFsmEffectTest {
    private val cfg = SessionConfig(1920, 1200, 240, forceFreeform = true)
    private fun fsmInAwaiting() = DesktopSessionFsm().also {
        it.send(FsmEvent.StartRequested(cfg)); it.send(FsmEvent.SurfaceReady)
        it.send(FsmEvent.DisplayAttached(7, it.generation))
    }
    @Test fun arms_15s_watchdog_when_settings_mutated() {
        val fx = fsmInAwaiting().let { it to it /* keep ref */ }
        // re-run to capture effects:
        val fsm = DesktopSessionFsm()
        fsm.send(FsmEvent.StartRequested(cfg)); fsm.send(FsmEvent.SurfaceReady)
        val gen = fsm.generation
        val effects = fsm.send(FsmEvent.DisplayAttached(7, gen))
        assertTrue(fsm.state.value is FsmState.AwaitingConfirmation)
        val wd = effects.filterIsInstance<FsmEffect.ScheduleWatchdog>().single()
        assertEquals(15_000L, wd.delayMs); assertEquals(gen, wd.generation)
        assertTrue(wd.sessionId.isNotBlank())
    }
    @Test fun user_confirmation_emits_cancel_and_enters_running() {
        val fsm = fsmInAwaiting(); val gen = fsm.generation
        val fx = fsm.send(FsmEvent.UserConfirmed(gen))
        assertTrue(fsm.state.value is FsmState.Running)
        assertEquals(gen, fx.filterIsInstance<FsmEffect.CancelWatchdog>().single().generation)
    }
    @Test fun expiry_rolls_back_and_keeps_journal_open() {
        val fsm = fsmInAwaiting(); val gen = fsm.generation
        val fx = fsm.send(FsmEvent.WatchdogExpired(gen))
        assertTrue(fsm.state.value is FsmState.RollingBack)
        assertTrue(fx.any { it is FsmEffect.RestoreJournal })
        assertEquals(false, fsm.state.value is FsmState.Idle)
    }
    @Test fun stale_generation_watchdog_is_ignored() {
        val fsm = fsmInAwaiting(); val before = fsm.state.value
        val fx = fsm.send(FsmEvent.WatchdogExpired(fsm.generation - 1))
        assertEquals(before, fsm.state.value)
        assertTrue(fx.isEmpty())
    }
}
```
(Add `assert/assertEquals/assertTrue` imports as in §A.)

### D. Staged on-device kill-9 plan — Redmi Pad Pro 5G / API 36 / HyperOS 3 / arm64-v8a

Baseline: record 7 keys (`settings get global <k>`) + `wm size`/`wm density`; `<pkg>=moe.n4tsu.dextop`. All commands via `adb shell`.

| Stage | Action | Expected / assertion |
|---|---|---|
| S0 Preflight | enter session; `dumpsys activity processes | grep dextop_watchdog`; `run-as <pkg> cat files/dextop_session/session_journal.wal` | exactly 1 detached `app_process` daemon; WAL has 7 `global:<modernKey>` mutates + 1 `wm` record + CRC valid |
| S1 Warm kill | record `appPid=$(pidof <pkg>)`; `kill -9 $appPid`; wait ≤1 s (sleep 1) | all 7 keys equal baseline, `wm` reset, overlay restored; receipt in `/data/local/tmp/dextop/watchdog/` |
| S2 Staggered kill | repeat S1 at t=3 s / 10 s / 14.5 s after ARM | rollback fires every time; no fractional key set; log `ROLLBACK{reason}` in logcat |
| S3 ACK negative | user confirms (→ FSM `UserConfirmed` + `SessionJournal.commit` → daemon ACK), then `kill -9` | **no** rollback; ACK receipt exists; no daemon process |
| S4 Daemon death / re-arm | `kill -9 $(pidof app_process … dextop_watchdog)`; do NOT ack; send new `ARM` | new daemon takes over abstract socket `@dextop.wd.<uid>` (single winner); old socket gone |
| S5 PID reuse | kill app, spawn processes until app PID reused; keep daemon armed | `/proc/<pid>/stat` field-22 starttime mismatch ⇒ no false "alive"; rollback still fires |
| S6 HyperOS battery | lock screen 10 min mid-session (Doze), then `kill -9` | rollback still fires (detached shell UID survives OEM reclaim); capture `dumpsys deviceidle` + logcat |
| S7 Clean teardown | user stops session (→ `cancelAllWatchdogsOnTeardown` + ACK) | no lingering `dextop_watchdog`, no stray `/data/local/tmp` receipts, all keys baseline, no late 15 s rollback |

Add `adb logcat -s DextopWatchdog:D SessionJournal:D` capture per stage; PII-scrub before attaching.

## Risks / device testing needed
- Shell `settings put` on hidden HyperOS keys may be rejected → verify S1/S6 key-by-key; fallback to app-side restore on next launch; attach `dumpsys` diff (W04_WATCHDOG:84).
- Two timers must not both fire: after ACK the daemon exits (receipt) and FSM `CancelWatchdog` removes the main-looper callback; assert no duplicate `RestoreJournal` in S3/S7 logcat.
- `sessionId` must be a UUID, not generation (generation resets across process death → stale ACK receipt collision).
- Abstract socket is per-user; multi-user switch (work profile) needs UID in name (done) — verify on device.
- CI-only build (`kotlin("test")` + `org.json`); no local Gradle. `kotlinx-coroutines-test` needed only if migrating FSM tests to `runTest` (W04_FSM.md:14).
