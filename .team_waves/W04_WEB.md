# W4C (Web) — Wave 2 (M2) Report

## Scope inspected
Web-only; no local source, no builds, no token. Topics: StateFlow/MVI/UDF for service state, structured concurrency in FGS, `ForegroundServiceStartNotAllowedException` on API 36, `kotlinx.coroutines-test`, StateFlow testing. GitHub trees + skill files read: `Kotlin/kotlin-agent-skills` (README, full tree), `haidrrrry/compose-kotlin-agent-skills` (root SKILL, architecture/testing sub-skills, `04-coroutines-flow.md`), `obra/superpowers` (TDD, systematic-debugging, verification-before-completion, full tree).

## Findings

**P0 — FGS start path is the #1 crash risk on API 36/HyperOS.** Since API 31, `startForegroundService()` from background throws `ForegroundServiceStartNotAllowedException` unless exempt (user-visible transition, user action on notification/widget/bubble, exact alarm, high-priority FCM, IME, geofence, device owner, companion-device permission, battery-opt-out, or SAW **with a currently visible overlay** for target 35+). Target 35+ also forbids `BOOT_COMPLETED` receivers launching `dataSync`/`camera`/`mediaPlayback`/`phoneCall`/`mediaProjection`/`microphone` FGS; while-in-use permissions are checked **at service creation** (permission check APIs lie in background); API 36 adds runtime job quotas for jobs started from an FGS. Field reports specifically show `specialUse` FGS launched from `BOOT_COMPLETED` throwing on Android 16. Fix: expose `DesktopSessionFsm.Phase.AwaitingForegroundStart`; only call `startForeground()` from an Activity/user action (or exact alarm), catch the exception at the service start boundary and reduce it to a typed `FgsStartDenied(reason)` FSM state + WAL record; never gate desktop start on `BOOT_COMPLETED`; if relying on SAW, verify `View.getWindowVisibility() == VISIBLE` first. [restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes), [API 15 behavior changes §FGS](https://developer.android.com/about/versions/15/behavior-changes-15), [field report](https://stackoverflow.com/questions/79793008/android-16-foregroundservice-specialuse-run-by-boot-completed-throw-android-a), [issuetracker](https://issuetracker.google.com/issues/454575931).

**P1 — Service coroutine scope must be injected and cancelled (BUG-02).** Official guidance: inject dispatchers/scopes, never `GlobalScope`, keep suspend fns main-safe via `withContext(io)`, cancel cooperative blocking loops with `ensureActive()`, use `coroutineScope`/`supervisorScope` for children, and **never swallow `CancellationException`** (`runCatching`/`catch (Throwable)` break structured concurrency). Fix: `DextopService` owns `CoroutineScope(SupervisorJob() + ioDispatcher)` cancelled in `onDestroy()`; sibling probe/WAL/watchdog jobs isolated under `supervisorScope`; 15 s detached watchdog gets its own Application-owned scope (explicitly cancelled at process teardown), **not** `GlobalScope`. [best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices), [exception handling](https://kotlinlang.org/docs/exception-handling.html), [haidrrrry 04-coroutines-flow.md §Structured Concurrency](https://github.com/haidrrrry/compose-kotlin-agent-skills/blob/main/references/04-coroutines-flow.md).

**P1 — FSM as SSOT: atomic `update` + immutable state.** Use `MutableStateFlow<SessionState>` private / `asStateFlow()` public; pure `reduce(state, event)` via `_state.update { }` (atomic, no naked `.value =`); sealed `state`/`event` hierarchies; one-shot side effects (WAL append, watchdog arm, notification) via `Channel<Effect>(BUFFERED).receiveAsFlow()`, never embedded in state; no mutable collections in state (ties to BUG-06: snapshot lists, synchronize or replace the access-order map). [StateFlow guide](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow), [state holders/UDF](https://developer.android.com/topic/architecture/ui-layer/stateholders), [haidrrrry architecture SKILL.md](https://github.com/haidrrrry/compose-kotlin-agent-skills/blob/main/skills/android-kotlin-architecture/SKILL.md), [root SKILL.md "MVI Guardrails"](https://github.com/haidrrrry/compose-kotlin-agent-skills/blob/main/SKILL.md).

**P2 — Testing contract (M2 gates).** `runTest` (one per test) skips delays and waits for children on the same `TestCoroutineScheduler`; inject `StandardTestDispatcher(testScheduler)`/`UnconfinedTestDispatcher`, share **one** scheduler, use `Dispatchers.setMain` only for local JVM tests; inject the service scope and pass `TestScope.backgroundScope` for never-completing collectors. StateFlow is conflated → assert `state.value`, not every emission; only use Turbine for event/effect flows; `stateIn` flows need an active collector or values never update. [coroutines test](https://developer.android.com/kotlin/coroutines/test), [flow test §Testing StateFlows](https://developer.android.com/kotlin/flow/test), [haidrrrry testing SKILL.md](https://github.com/haidrrrry/compose-kotlin-agent-skills/blob/main/skills/android-kotlin-testing/SKILL.md).

**P2 — Skill-file applicability to M2 (exact paths).** Directly apply: `haidrrrry/compose-kotlin-agent-skills/skills/android-kotlin-architecture/SKILL.md` (MVI/UDF, `_state.update`, sealed events/effects), `references/04-coroutines-flow.md` (StateFlow/Channel, cancellation, supervisorScope, `runCatching` trap), `skills/android-kotlin-testing/SKILL.md` + `references/11-testing.md` (Turbine/fakes), `references/00-banned-antipatterns.md` (review gate), `SKILL.md` root (MVI guardrails), `examples/todo-mvi/TodoViewModel.kt` (reference shape). `obra/superpowers/skills/systematic-debugging/SKILL.md` (BUG-01..06: root cause before fix), `skills/test-driven-development/SKILL.md` + `writing-good-tests.md` (WAL/FSM red-green), `skills/verification-before-completion/SKILL.md` (CI evidence before "done"), `skills/subagent-driven-development/` (wave orchestration). **Not directly M2:** `Kotlin/kotlin-agent-skills` — full tree is `kotlin-tooling-*` (AGP9/KMP/CocoaPods/Gradle toolchain) and `kotlin-backend-jpa-entity-mapping`; no coroutine/StateFlow/testing skill. Its useful item is the Kotlin docs MCP (`https://mcp.kotlinlang.org/mcp`) for live API lookup ([README](https://github.com/Kotlin/kotlin-agent-skills/blob/main/README.md)); don't invent a "Kotlin coroutines skill" there. haidrrrry's `android-kotlin-compose`/`02-compose-ui` files target M3, not M2. [trees](https://api.github.com/repos/Kotlin/kotlin-agent-skills/git/trees/HEAD?recursive=1), [tree](https://api.github.com/repos/haidrrrry/compose-kotlin-agent-skills/git/trees/HEAD?recursive=1), [tree](https://api.github.com/repos/obra/superpowers/git/trees/HEAD?recursive=1).

**P3 — Version pins conflict with target.** haidrrrry skill pins compileSdk/targetSdk 35+, AGP 9, coroutines 1.10.2; Dextop targets API 36 with minSdk 33. Reuse patterns only; build/verify exclusively in GitHub Actions per brief. [root SKILL.md](https://github.com/haidrrrry/compose-kotlin-agent-skills/blob/main/SKILL.md).

## Deliverable (patterns + citations)

```kotlin
data class SessionState(val phase: Phase, val backend: BackendKind? = null,
                        val error: SessionError? = null)   // immutable, sealed Phase/Error
private val _state = MutableStateFlow(SessionState(Idle))
val state: StateFlow<SessionState> = _state.asStateFlow()
private val _effects = Channel<SessionEffect>(Channel.BUFFERED)
fun on(e: SessionEvent) { when (e) { /* pure reduce */ -> _state.update { reduce(it, e) } } }

class DextopService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + ioDispatcher) // injected
  override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
    scope.launch {
      try { startForeground(NOTIF_ID, notification()) }
      catch (e: ForegroundServiceStartNotAllowedException) {
        on(SessionEvent.FgsStartDenied(e.message ?: "denied")) // FSM + WAL
      }
    }
    return START_NOT_STICKY // explicit restart policy, no implicit relaunch from boot
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
```

WAL: append mutated keys as atomic JSON from the effect consumer on the same scope; restore all keys (BUG-01) before emitting first non-`Loading` state; test with `runTest { service.on(...); advanceUntilIdle(); assertEquals(expected, service.state.value) }` plus fake repository/dispatcher injection. Watchdog: separate Application-owned `CoroutineScope(SupervisorJob() + Dispatchers.Default)` + cancellable `delay(15_000)`, `ensureActive()` in blocking probe loops. All citations are in Findings; primary sources: [FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [FGS changes](https://developer.android.com/develop/background-work/services/fgs/changes), [coroutines best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices), [coroutines test](https://developer.android.com/kotlin/coroutines/test), [flow test](https://developer.android.com/kotlin/flow/test), [state holders](https://developer.android.com/topic/architecture/ui-layer/stateholders).

## Risks / device testing needed
- HyperOS 3 battery/background policies may be stricter than AOSP: verify user-visible start, notification action, and exact-alarm paths on the Redmi Pad Pro (API 36), not emulator only.
- Repro `specialUse` FGS from `BOOT_COMPLETED` (reported throw on Android 16); verify our start path never uses it and that denial lands in FSM+WAL.
- Verify SAW overlay exemption requires a visible `TYPE_APPLICATION_OVERLAY` window; test overlay-hidden case.
- `adb shell am kill` mid-session: WAL restores all 7 `modernKeys`, 15 s watchdog fires once, no duplicate FGS starts.
- API 36 job quotas for jobs started from FGS; thermal/doze behavior of the 15 s watchdog on 120 Hz tablet; no local builds (GitHub Actions only).
