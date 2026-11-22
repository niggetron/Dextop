# W4A (M2) — Wave 04 Report: DesktopSessionFsm + MirrorService decomposition

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/MirrorService.kt` (14,103 LOC, `class MirrorService : AccessibilityService(), SurfaceHolder.Callback`; ~200 mutable fields, ~436 methods).
- `SessionJournal.kt` (381 LOC, atomic CRC/seq NDJSON WAL, `preparing/recordSetting/recordWm/running/paused/restoreAll/clear/snapshot`), `DesktopModeConfigurator.kt` (200 LOC, session-scoped IO `CoroutineScope`, cancellable `configureDisplay`, `restore`), `PhoneRotationController.kt`, `InternalRefreshRateController.kt`, `AndroidAutoMirrorActivity.kt` (external journal owners).
- `android/app/build.gradle.kts` (Kotlin 2.2.20, minSdk 33, no explicit coroutines dep).

## Findings
- **P0 — phase is implicit in ~20 booleans/counters, not a state.** `MirrorService.kt:221,1134-1144,1298-1334,1334,1395-1396,1409-1423,1543-1555`: `active`, `stopping`, `pausedForAndroid`, `displayCreationInProgress`, `serviceDestroyed`, `targetDisplayId`, `mirrorDisplayId`, `suspendedForLockScreen`, plus `mirrorRefreshGeneration`, `hostReconfigurationGeneration`, `stopCleanupGeneration`, `laptopModeEvaluationGeneration`, `imeRegionSessionGeneration`, etc. Lifecycle entry points are disjoint: `onServiceConnected:2110`, `start:2897`, surface callbacks `12030-12066`, `temporarilyReturnToAndroid:8328`, `suspendForLockScreen:11306`, `stop:13868`, `awaitStoppedDisplay:13943`, `finishStop:13970`, `onUnbind:2820`, `onDestroy:2839`. Vendor display/surface callbacks on HyperOS arrive reordered (explicit comments at `8351-8355`, `13923-13925`); each path guards with a different generation, so stale callbacks can re-enter teardown or reapply settings after restore. Fix: sealed State + Event + generation-carried effects; reducer drops events whose generation ≠ current.
- **P0 — `start()` is a 150-line non-atomic mutation burst** (`2897-3055`): resets ~40 fields, opens journal (`2970`), applies rotation (`3027`), removes/adds windows (`3038-3049`); failures exit through 9 distinct `completeStart(...)` sites (`2949,12146,12212,12240,12323,12421,12441,12460,12613,12633,12951,14030`) leaving partial flags. Fix: reducer computes the whole next state, then an effect list runs; on failure emit `Failed(snapshot,error)` and one rollback path.
- **P1 — recovery is stringly-typed and duplicated.** `onServiceConnected:2171` checks `sessionJournal.snapshot()["transactionOpen"]`, restores topology + settings; `finishStop:14010-14023` restores again and clears; `temporarilyReturnToAndroid:8389` deliberately keeps WAL open; `AndroidAutoMirrorActivity:647,715-724,777,821-822` has its own copy. No idempotence token. Fix: `Recovering` state + `RestoreCompleted(ok,generation)`; journal/WAL remains the durable source, FSM only orders the restore.
- **P1 — BUG-02 fix is partial.** `DesktopModeConfigurator.kt:84-101,182-190` cancels its own jobs, but MirrorService still posts uncancellable main-looper chains: `pauseDisableSelfRunnable` 320 ms + 4.5 s (`8357-8407`), `awaitStoppedDisplay` 40×50 ms (`13953`), `waitForOverlay:12446`, `hostDisplayMonitor:2057`, `scheduleVirtualMouseReadyCheck:4624`, many `postDelayed`. On Android 16 these outlive a stop and overwrite freshly restored windowing mode. Fix: all delayed work becomes `FsmEffect.ScheduleWatchdog/ScheduleDelayed(generation)`; single main `Handler(Looper.getMainLooper())` dispatcher removes callbacks by generation on every leaving transition.
- **P2 — blueprint's 15 s detached watchdog is absent.** Closest are 4.5 s pause detach and ~2 s display-await. Fix: FSM owns `ScheduleWatchdog(15_000, generation)`; if display removal + WAL restore have not reached a terminal state, keep journal open and report degraded restore (device: vendor display service that never emits removal callback, see `13936-13941`).
- **P2 — `StateFlow` dependency not declared.** `app/build.gradle.kts:206-234` has no `kotlinx-coroutines`; reachable only transitively via Compose. Add explicit `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")` (+ `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")`) before wiring the FSM.
- **P3 — test seam exists.** `testImplementation(kotlin("test"))` + `org.json` already present; `SessionWalCodec` (`SessionJournal.kt:362-381`) is deliberately pure/JVM-testable. Reducer must have zero Android imports so it is unit-testable the same way.

## Deliverable

### 1. Kotlin skeleton — `app/src/main/kotlin/moe/n4tsu/dextop/session/DesktopSessionFsm.kt`

```kotlin
package moe.n4tsu.dextop.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SessionConfig(
    val width: Int, val height: Int, val density: Int,
    val secure: Boolean = false, val decorations: Boolean = false,
    val autoOnly: Boolean = false, val forceFreeform: Boolean = false,
)

internal data class SessionCtx(
    val config: SessionConfig,
    val generation: Long,          // ++ on every StartRequested; carried by ALL effects/callbacks
    val displayId: Int = -1,
    val cleanupGeneration: Long = 0,
)

internal sealed interface FsmState {
    data object Idle : FsmState
    data class Starting(val s: SessionCtx) : FsmState          // accessibility enable handshake / pending
    data class Preparing(val s: SessionCtx) : FsmState         // journal open, host Surface/window pending
    data class CreatingDisplay(val s: SessionCtx) : FsmState   // overlay request -> attach in flight
    data class Running(val s: SessionCtx) : FsmState           // displayId >= 0, journal.running()
    data class PausedForAndroid(val s: SessionCtx) : FsmState  // journal.paused(); service intentionally alive
    data class SuspendedForLockScreen(val s: SessionCtx) : FsmState
    data class Stopping(val s: SessionCtx) : FsmState          // await removal + restore
    data class Recovering(val s: SessionCtx?) : FsmState       // WAL replay on service connect
    data class Failed(val s: SessionCtx?, val error: Throwable, val from: FsmState) : FsmState
}

internal sealed interface FsmEvent {
    data class StartRequested(val config: SessionConfig) : FsmEvent
    data object ServiceConnected : FsmEvent
    data class SurfaceReady(val width: Int, val height: Int) : FsmEvent
    data class HostSurfaceDestroyed : FsmEvent
    data class HostResized(val width: Int, val height: Int) : FsmEvent
    data class DisplayAttached(val displayId: Int, val generation: Long) : FsmEvent
    data class DisplayRemoved(val displayId: Int, val generation: Long) : FsmEvent
    data object PauseRequested : FsmEvent
    data object ResumeRequested : FsmEvent
    data object ScreenOff : FsmEvent
    data object UserPresent : FsmEvent
    data object StopRequested : FsmEvent
    data class RestoreCompleted(val ok: Boolean, val generation: Long) : FsmEvent
    data class WatchdogExpired(val generation: Long) : FsmEvent
    data class Failed(val error: Throwable) : FsmEvent
}

internal sealed interface FsmEffect {
    data class OpenJournal(val s: SessionCtx) : FsmEffect                    // sessionJournal.preparing
    data class MarkRunning(val s: SessionCtx) : FsmEffect                    // sessionJournal.running(id)
    data object MarkPaused : FsmEffect
    data class CancelConfigurator(val gen: Long) : FsmEffect                 // DesktopModeConfigurator.cancelPendingWork
    data class ConfigureDisplay(val displayId: Int, val gen: Long) : FsmEffect
    data class CreateDisplay(val w: Int, val h: Int, val gen: Long) : FsmEffect
    data class ReattachDisplay(val w: Int, val h: Int, val gen: Long) : FsmEffect
    data object ReleaseMirror : FsmEffect
    data class AwaitDisplayRemoval(val displayId: Int, val gen: Long) : FsmEffect
    data class RestoreJournal(val gen: Long) : FsmEffect                     // topology + settings + wallpaper
    data class ClearJournal(val gen: Long) : FsmEffect
    data class CompleteStart(val result: Result<Map<String, Any>>) : FsmEffect
    data class Schedule(val ms: Long, val gen: Long, val tag: String) : FsmEffect // single cancellable main Handler
    data object CancelScheduled : FsmEffect
    data class Log(val tag: String, val msg: String) : FsmEffect
}

internal data class Transition(val state: FsmState, val effects: List<FsmEffect> = emptyList())
private fun FsmState.none() = Transition(this)
private fun FsmState.then(vararg e: FsmEffect) = Transition(this, e.toList())

internal class DesktopSessionFsm(
    initial: FsmState = FsmState.Idle,
    private val dispatch: (List<FsmEffect>) -> Unit,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<FsmState> = _state.asStateFlow()
    val gen: Long get() = generationOf(_state.value)

    /** Main-looper only. Reducer is pure; effects are the only side effects. */
    fun send(event: FsmEvent) {
        val from = _state.value
        val t = from.accept(event)
        if (t.state === from && t.effects.isEmpty()) return
        _state.value = t.state
        dispatch(t.effects)
    }

    /** Callbacks from binder/sensor/IO threads: drop stale generations without touching StateFlow off-main. */
    fun sendIfCurrent(event: FsmEvent, eventGen: Long, post: ((FsmEvent) -> Unit)? = null) {
        if (eventGen != gen) return
        if (post != null) post(event) else send(event)
    }
}

internal val FsmState.ctxOrNull: SessionCtx? get() = when (this) {
    is FsmState.Idle -> null
    is FsmState.Starting -> s
    is FsmState.Preparing -> s
    is FsmState.CreatingDisplay -> s
    is FsmState.Running -> s
    is FsmState.PausedForAndroid -> s
    is FsmState.SuspendedForLockScreen -> s
    is FsmState.Stopping -> s
    is FsmState.Recovering -> s
    is FsmState.Failed -> s
}
internal fun generationOf(s: FsmState): Long = s.ctxOrNull?.generation ?: -1L

private fun FsmState.accept(e: FsmEvent): Transition = when (e) {
    is FsmEvent.StartRequested -> when (this) {
        is FsmState.Idle, is FsmState.Failed, is FsmState.PausedForAndroid -> {
            val next = SessionCtx(e.config, (generationOf(this) + 1).coerceAtLeast(1))
            Transition(FsmState.Preparing(next), listOf(
                FsmEffect.CancelConfigurator(generationOf(this)), FsmEffect.CancelScheduled,
                FsmEffect.OpenJournal(next), FsmEffect.ConfigureDisplay(-1, next.generation),
            ))
        }
        is FsmState.Running -> this.then(
            FsmEffect.Log("DisplayGeometry", "reuse running display; resize via resizeActiveDisplay"),
            FsmEffect.CompleteStart(Result.success(emptyMap())), // real handler builds the map from s
        )
        else -> this.then(FsmEffect.CompleteStart(Result.failure(
            IllegalStateException("start rejected in ${this::class.simpleName}"))))
    }
    is FsmEvent.ServiceConnected -> when (this) {
        is FsmState.Idle -> FsmState.Recovering(null).then(
            FsmEffect.RestoreJournal(-1L), FsmEffect.Schedule(15_000L, -1L, "detached_watchdog"))
        else -> this.none()
    }
    is FsmEvent.SurfaceReady -> when (this) {
        is FsmState.Preparing -> FsmState.CreatingDisplay(s).then(
            FsmEffect.CreateDisplay(e.width, e.height, s.generation),
            FsmEffect.Schedule(15_000L, s.generation, "display_create"))
        is FsmState.Running -> this.none() // reattach path is HostSurfaceDestroyed -> Preparing -> SurfaceReady
        else -> this.none()
    }
    is FsmEvent.HostSurfaceDestroyed -> when (this) {
        is FsmState.Running, is FsmState.CreatingDisplay -> FsmState.Preparing(ctxOrNull!!).then(
            FsmEffect.ReleaseMirror, FsmEffect.CancelScheduled)
        else -> this.none()
    }
    is FsmEvent.DisplayAttached -> when (this) {
        is FsmState.CreatingDisplay -> if (e.generation != s.generation) this.none() else {
            val attached = s.copy(displayId = e.displayId)
            FsmState.Running(attached).then(
                FsmEffect.MarkRunning(attached), FsmEffect.CancelScheduled,
                FsmEffect.Log("DisplayBackend", "display attached id=${e.displayId}"))
        }
        else -> this.none()
    }
    is FsmEvent.DisplayRemoved -> when (this) {
        is FsmState.Running, is FsmState.Stopping -> if (e.generation != ctxOrNull!!.generation) this.none()
            else FsmState.Stopping(ctxOrNull!!).then(FsmEffect.RestoreJournal(ctxOrNull!!.generation))
        else -> this.none()
    }
    is FsmEvent.StopRequested -> when (this) {
        is FsmState.Running, is FsmState.CreatingDisplay, is FsmState.Preparing,
        is FsmState.PausedForAndroid, is FsmState.SuspendedForLockScreen -> FsmState.Stopping(ctxOrNull!!).then(
            FsmEffect.CancelConfigurator(s.generation), FsmEffect.CancelScheduled,
            FsmEffect.ReleaseMirror, FsmEffect.AwaitDisplayRemoval(s.displayId, s.generation),
            FsmEffect.Schedule(15_000L, s.generation, "detached_watchdog"))
        else -> this.none()
    }
    is FsmEvent.PauseRequested -> when (this) {
        is FsmState.Running -> FsmState.PausedForAndroid(s).then(
            FsmEffect.MarkPaused, FsmEffect.CancelScheduled,
            FsmEffect.Schedule(4_500L, s.generation, "pause_detach"))
        else -> this.none()
    }
    is FsmEvent.ResumeRequested -> when (this) {
        is FsmState.PausedForAndroid -> FsmState.Preparing(s).then(
            FsmEffect.OpenJournal(s), FsmEffect.CancelScheduled)
        else -> this.none()
    }
    is FsmEvent.ScreenOff -> when (this) {
        is FsmState.Running -> FsmState.SuspendedForLockScreen(s).then(FsmEffect.CancelScheduled)
        else -> this.none()
    }
    is FsmEvent.UserPresent -> when (this) {
        is FsmState.SuspendedForLockScreen -> FsmState.Running(s).then(
            FsmEffect.Schedule(1_000L, s.generation, "unlock_verify"))
        else -> this.none()
    }
    is FsmEvent.RestoreCompleted -> when (this) {
        is FsmState.Stopping -> if (e.generation != s.generation) this.none() else
            if (e.ok) FsmState.Idle.then(FsmEffect.ClearJournal(s.generation),
                FsmEffect.CancelScheduled,
                FsmEffect.CompleteStart(Result.failure(IllegalStateException("session stopped"))))
            else FsmState.Failed(s, IllegalStateException("restore incomplete"), this)
        is FsmState.Recovering -> if (!e.ok) FsmState.Failed(s, IllegalStateException("recovery failed"), this)
            else FsmState.Idle.then(FsmEffect.CancelScheduled)
        else -> this.none()
    }
    is FsmEvent.WatchdogExpired -> when (this) {
        is FsmState.CreatingDisplay, is FsmState.Stopping, is FsmState.Recovering ->
            FsmState.Failed(s, IllegalStateException("detached watchdog: ${this::class.simpleName}"), this)
                .then(FsmEffect.Log("Lifecycle", "journal kept open for next launch recovery"))
        else -> this.none() // generation check performed by sendIfCurrent
    }
    is FsmEvent.Failed -> when (this) {
        is FsmState.Running, is FsmState.CreatingDisplay, is FsmState.Preparing ->
            FsmState.Failed(ctxOrNull, e.error, this).then(
                FsmEffect.CancelConfigurator(generationOf(this)),
                FsmEffect.CancelScheduled, FsmEffect.CompleteStart(Result.failure(e.error)))
        else -> this.none()
    }
    is FsmEvent.HostResized -> this.then(
        FsmEffect.Log("DisplayGeometry", "host resize ${e.width}x${e.height} -> resizeActiveDisplay"))
}
```

Bridge in `MirrorService` (only Android-touching class; all effects executed on main):

```kotlin
private lateinit var fsm: DesktopSessionFsm
private val fsmHandler = Handler(Looper.getMainLooper())
private fun MirrorService.Config.toFsm() = SessionConfig(width,height,density,secure,decorations,autoOnly,forceFreeform)

override fun onCreate() { // or first use from onServiceConnected
    fsm = DesktopSessionFsm { effects -> effects.forEach(::runEffect) }
}
private fun emit(e: FsmEvent) {
    if (Looper.myLooper() == Looper.getMainLooper()) fsm.send(e)
    else fsmHandler.post { fsm.send(e) }
}
private fun runEffect(fx: FsmEffect) = when (fx) {
    is FsmEffect.OpenJournal -> sessionJournal.preparing(/* from fx.s.config */)
    is FsmEffect.MarkRunning -> sessionJournal.running(fx.s.displayId)
    is FsmEffect.MarkPaused -> sessionJournal.paused()
    is FsmEffect.CancelConfigurator -> desktopModeConfigurator.cancelPendingWork()
    is FsmEffect.AwaitDisplayRemoval -> awaitStoppedDisplay(fx.displayId, ..., fx.gen, 0)
    is FsmEffect.RestoreJournal -> restoreAllForSession(fx.gen) { ok -> emit(FsmEvent.RestoreCompleted(ok, fx.gen)) }
    is FsmEffect.ClearJournal -> sessionJournal.clear()
    is FsmEffect.Schedule -> scheduleGenerationChecked(fx.ms, fx.gen, fx.tag) { emit(FsmEvent.WatchdogExpired(fx.gen)) }
    is FsmEffect.CancelScheduled -> cancelAllGenerationCallbacks()
    is FsmEffect.CompleteStart -> completeStart(fx.result)
    is FsmEffect.Log -> OperationLog.i(this, fx.tag, fx.msg)
    // CreateDisplay/ConfigureDisplay/ReleaseMirror delegate to extracted coordinators
    else -> Unit
}
```

All async callbacks pass the captured generation: `emit(FsmEvent.DisplayAttached(id, gen))`, but only after `if (gen != fsm.gen) return`.

### 2. Domain coordinators (target end state)

| Coordinator | Moves out of MirrorService | Field cluster |
|---|---|---|
| `SessionCoordinator` (FSM host) | `launch`, `start`, `stop`, `awaitStoppedDisplay`, `finishStop`, `suspendForLockScreen`, `resumeAfterUnlock`, `temporarilyReturnToAndroid`, `completeStart`, `onUnbind/onDestroy` bodies | `active`, `pending*`, `stopping`, `stopCleanupGeneration`, `pausedForAndroid`, `suspended*`, `screenLifecycleGeneration` |
| `DisplayCoordinator` | `surfaceCreated/Changed/Destroyed`, `createDisplay*`, `reattachExistingDisplay`, `waitForOverlay*`, `attachDesktopDisplay`, `attachMirror/releaseMirror`, `configureDisplay`, `resizeActiveDisplay`, `displayListener` | `targetDisplayId`, `mirrorDisplayId`, `displayCreationInProgress`, `mirrorRefreshGeneration`, `hostReconfigurationGeneration`, `autoDestinationSurface`, `phoneOwnedDisplay`, `autoOwnedDisplay` |
| `SettingsRestorationCoordinator` | WAL-driven restore: `onServiceConnected:2169-2177`, `finishStop:13974-14022`, `temporarilyReturnToAndroid:8389`, plus `SessionJournal` + `DesktopModeConfigurator` + `PhoneRotationController` + `InternalRefreshRateController` + `DisplayEnvironmentSettings` | `sessionJournal`, `desktopModeConfigurator`, `phoneRotationController` |
| `InputCoordinator` | pointer/mouse/touchpad/gamepad/event injection, `privilegedInputClient*`, `LaptopSwipeDecoder` bridge | `cursor*`, `virtualMouse*`, `virtualTouchpad*`, `gamepad*`, modifier sets |
| `LaptopModeCoordinator` | hinge/fold posture, laptop deck/keyboard/swipe UI | `laptop*`, `hinge*`, `foldingApi*`, swipe executors |
| `ExternalOutputCoordinator` | Cast, external/extended displays | `cast*`, `external*`, `extended*` |
| `UiCoordinator` | menu, demo, workspace, resolution/volume/brightness | `menu*`, `demo*`, `workspace*`, `root`, `windowManager` |

### 3. Migration order (every step is CI-green on its own; no local builds)

1. **Add FSM file + pure reducer unit tests only.** Not wired in. Dead code compiles; tests run with `kotlin("test")`.
2. **Mechanical visibility widening.** `private` → `internal` on every MirrorService member that extracted code will read/write. Kotlin `internal` is module-wide, so top-level extensions in the same package/module can access them. Zero behavior change.
3. **Extract `SettingsRestorationCoordinator` first.** It is already ~90% extracted; introduce one `restoreAllForSession(gen, onDone)` + `beginSession(config)` entry, route the 4 journal-owning call sites through it. Highest bug-value (BUG-01/02), smallest surface. Verify WAL still records all keys.
4. **Cut leaf extension files.** `DisplayGeometry.kt`, `SessionLogging.kt`: move method bodies verbatim into `internal fun MirrorService.xxx(...)`; no call-site edits.
5. **Extract `DisplayCoordinator.kt`** as `internal fun MirrorService.*` extensions (~1.2k LOC). Keep every `override fun surface*/onDisplay*` in MirrorService as one-line delegates (extensions cannot be `override`). Build.
6. **Wire the FSM.** Replace phase *reads* (`active || stopping || pending != null`, `sessionJournal.snapshot()["transactionOpen"]`, `displayCreationInProgress`, `pausedForAndroid`) with `fsm.state.value is FsmState.X` while still shadow-writing the old flags; add a debug assertion that they agree. Emit events from lifecycle entry points; keep old flags as read-only fallbacks for not-yet-migrated code.
7. **Extract `InputCoordinator`, `LaptopModeCoordinator`, `ExternalOutputCoordinator`, `UiCoordinator`**, one file per PR, same verbatim-extension technique; move listeners into each coordinator only after its methods are extracted.
8. **Delete shadow flags** once no readers remain; FSM state is the only source of truth. Remove `snapshot()["transactionOpen"]` string checks entirely. Update `MirrorService.isActive()` etc. to `fsm.state.value is FsmState.Running || ...`.

**Compilation invariant (the trick that keeps 14k LOC green):** never introduce an interface or constructor injection during a cut. Move a method body unchanged into a new file as `internal fun MirrorService.method(...)`, widen only the members it touches to `internal`, and leave every call site untouched. `override`/`by lazy`/companion members stay in the class (companion `completeStart` becomes `internal` or a top-level `internal fun MirrorService.completeStart`). Because Kotlin resolves extensions statically, all private-state access still lands on the same instance. Each extracted file is a pure cut-and-paste PR; the FSM wiring (step 6) is the only semantic PR and is isolated so a regression is trivially revertible.

## Risks / device testing needed
- **Coroutines dependency**: add explicit coroutines-android/test deps (currently transitive via Compose only) — otherwise `StateFlow` compiles by accident and breaks on Compose upgrade.
- **Main-thread ordering**: `StateFlow` updates must occur on the main looper; binder/sensor callbacks use `sendIfCurrent`/post. Verify no StateFlow access from `privilegedInputClient`, hinge `SensorEventListener`, or `DisplayManager.DisplayListener` threads.
- **Generation correctness on HyperOS 3 / Redmi Pad Pro 5G**: test stale `surfaceCreated`, delayed 250/750 ms freeform reapply, fold/half-open surface recreation, lock-screen suspend/resume, Auto + cover concurrent sessions, and Recents swipe-to-remove. Assert no settings restore happens before the display disappears (`13923-13925`) and no stale detach disables the accessibility service (`8393-8407`).
- **WAL/FSM split brain**: journal is durable truth, FSM is in-memory; after process death, `onServiceConnected → Recovering` must restore WAL before any `Preparing` is accepted. Test with `adb shell am force-stop` mid-`attachDesktopDisplay` and verify all 7 `modernKeys` restored (BUG-01).
- **15 s watchdog semantics**: confirm it never clears the journal on a vendor display service that omits the removal callback; keep journal open and surface the recovery card.
- CI-only build verification (no local Gradle per brief).
