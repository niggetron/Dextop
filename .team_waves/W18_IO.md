# W18B (implementation design) — Wave 18 Report

## Scope inspected
`DesktopModeConfigurator.kt` (200 L), `MirrorService.kt` (`surfaceCreated`/`createDisplay`/`createDirectPhoneDisplay`/`createHeadlessDisplay` 12039–12254, `postMainDelayed`/`waitForOverlay*` 12383–12478, `configureDisplay`/`applyDisplayRotation` 12714–12825, `stop`/`awaitStoppedDisplay`/`finishStop` 13879–14039), `AndroidAutoMirrorActivity.kt:659` (`removeCallbacksAndMessages` reference pattern), `app/build.gradle.kts` (coroutines available transitively via Compose M3 — `DesktopModeConfigurator.kt` already imports `kotlinx.coroutines`). No builds, no source edits.

## Findings (design anchored to W17 P2)
- **P2-a** `MirrorService.kt:12139,12179,12241` → `DesktopModeConfigurator.kt:62-76`: `applyForCurrentDevice()` does `Settings.Global.putString` binder writes + `SessionJournal.rememberGlobal()` → `out.fd.sync()` on the **main** looper. W17 fix: make it an IO `Job` with a generation guard; `remember*()` only from IO.
- **P2-b** `MirrorService.kt:12714-12765` hidden `IWindowManager` reflection (`setIgnoreOrientationRequest`, `setFixedToUserRotation`, `setShouldShowSystemDecors`, `setDisplayImePolicy`) + `:12793-12825` `freezeDisplayRotation` run on main from `:12502,:13410`. W17 fix: dispatch to session IO with generation guard; cancel on stop.
- **P2-c** `MirrorService.kt:12383-12385`: `Handler(mainLooper)` allocated per `postMainDelayed()` call, never cancelled; `waitForOverlay():12457-12477` has no active/stopping guard and can `attachDesktopDisplay()` after stop. W17 fix: one retained main Handler, `removeCallbacksAndMessages(null)` in `stop()`.
- **P2-d** `DesktopModeConfigurator.kt:182-190`: `cancelPendingWork()` cancels but does not wait; non-interruptible `commandRunner` calls (`:112,120,143,161,164`) can land after `restore():193`. W17 fix: defer restore until cancelled jobs complete.

## Deliverable — exact Kotlin patches

### Patch 1 — `DesktopModeConfigurator.kt`: IO apply, generation guard, restore-waits

Add imports:
```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
```

Replace fields `:48-53` with:
```kotlin
private val scopeLock = Any()
private val applyGate = Any()
private val freeformGate = Any()
@Volatile private var sessionScope: CoroutineScope = newSessionScope()
@Volatile private var applyGeneration = 0L
@Volatile private var quiescence: CompletableDeferred<Unit>? = null
private val displayJobs = ConcurrentHashMap<Int, Job>()
// Teardown scope is never cancelled by a session stop; it owns the restore.
private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun newSessionScope(): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
```

Replace `applyForCurrentDevice()` `:55-77` with:
```kotlin
/** IO-dispatched; returns a Job so callers can chain continuations. */
fun applyForCurrentDevice(): Job {
    synchronized(applyGate) {
        if (applied) return CompletableDeferred(Unit) // completed no-op
        applied = true
    }
    val gen = applyGeneration
    val scope = synchronized(scopeLock) { sessionScope }
    return scope.launch {
        try {
            applyForCurrentDeviceNow(gen)
        } catch (t: Throwable) {
            OperationLog.w(context, "DesktopMode", "applyForCurrentDevice aborted", t)
        }
    }
}

private suspend fun applyForCurrentDeviceNow(gen: Long): List<StrategyAttempt> {
    if (environment.platformManaged) {
        OperationLog.i(context, "DesktopMode", "platform-managed environment=${environment.id}")
        return listOf(StrategyAttempt("platform_managed", true, environment.id))
    }
    return environment.temporaryGlobalSettings.map { (key, value) ->
        coroutineContext.ensureActive()
        if (gen != applyGeneration) {
            return@map StrategyAttempt("global:$key", false, "cancelled: generation changed")
        }
        runCatching {
            val previous = Settings.Global.getString(resolver, key)
            savedGlobals += SavedGlobal(key, previous)
            sessionJournal.rememberGlobal(key, previous) // fd.sync() now on IO
            check(Settings.Global.putString(resolver, key, value)) {
                moe.n4tsu.dextop.localText("uiSettingRejected")
            }
            if (gen != applyGeneration) throw CancellationException("generation changed after write: $key")
            StrategyAttempt("global:$key", true, "$previous -> ${value ?: "<deleted>"}")
        }.getOrElse {
            OperationLog.w(context, "DesktopMode", "optional global setting failed key=$key", it)
            StrategyAttempt("global:$key", false, it.message.orEmpty())
        }
    }
}
```
`applied` is claimed eagerly under `applyGate` so two rapid main-thread calls cannot both enter; `gen` is captured at launch and re-checked before every write (also after it, so a stale write cannot mutate post-restore state).

Replace `cancelPendingWork()`/`restore()` `:181-199` with:
```kotlin
/** Non-blocking, main-safe. Returns after children are cancelled, not after they finish. */
fun cancelPendingWork() {
    val (oldRoot, children) = synchronized(scopeLock) {
        applyGeneration++
        val prev = sessionScope
        sessionScope = newSessionScope()
        val root = prev.coroutineContext[Job]
        val kids = root?.children?.toList().orEmpty()
        quiescence = CompletableDeferred()
        root to kids
    }
    displayJobs.clear()
    oldRoot?.cancel()
    val done = quiescence!!
    teardownScope.launch {
        children.joinAll() // wait for non-interruptible commandRunner calls to actually return
        done.complete(Unit)
    }
}

/** Restores only after cancelled jobs quiesce; result delivered on main. */
fun restore(onRestored: (Boolean) -> Unit): Job = teardownScope.launch {
    quiescence?.join()
    val ok = runCatching { sessionJournal.restoreSystemSettings() }.isSuccess
    OperationLog.i(context, "DesktopMode", "restored settings count=${savedGlobals.size}")
    savedGlobals.clear()
    applied = false
    forcedFreeformGlobalsApplied = false
    withContext(Dispatchers.Main) { onRestored(ok) }
}
```
`configureDisplay()` `:84-102` stays as-is (already IO + `displayJobs` cancel on same scope) — it inherits the generation guard via `cancelPendingWork()` swapping `sessionScope`.

### Patch 2 — `MirrorService.kt`: one retained main Handler + generation-cancelled callbacks

Add imports:
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

Add instance fields near `:1144-1153`:
```kotlin
private val mainHandler: Handler = Handler(Looper.getMainLooper()) // single retained main handler
@Volatile private var callbackGeneration = 0L
@Volatile private var configGeneration = 0L
private val sessionIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

Replace `postMainDelayed()` `:12383-12385`:
```kotlin
private fun postMainDelayed(delayMs: Long, action: () -> Unit) {
    val gen = callbackGeneration
    mainHandler.postDelayed({
        if (gen != callbackGeneration || stopping) return@postDelayed
        action()
    }, delayMs)
}
```
`waitForOverlay():12457` and `waitForOverlayRequestCleared():12403` therefore cannot call `attachDesktopDisplay()` after stop even though `waitForOverlay` has no own guard.

In `stop()` `:13879-13883`, add before `cancelPendingWork()`:
```kotlin
callbackGeneration++          // invalidate every queued postMainDelayed callback
configGeneration++            // invalidate every in-flight hidden-IWm config block
mainHandler.removeCallbacksAndMessages(null) // BUG-02/P2: drop all retained delayed callbacks
```
(Also cancel session IO work at stop: `sessionIoScope.coroutineContext[Job]?.cancel()` then re-create — mirror `DesktopModeConfigurator.newSessionScope()` if the service can be reused; otherwise create the scope in `start()` and cancel here.)

### Patch 3 — `MirrorService.kt`: hidden `IWindowManager` config on IO with generation guard

Replace `configureDisplay()` `:12714-12766`:
```kotlin
private fun configureDisplay(skipWindowing: Boolean = false) {
    val gen = configGeneration
    val targetId = targetDisplayId
    val decorations = showSystemDecorations
    val autoOnly = autoOnlySession
    sessionIoScope.launch {
        fun alive() = gen == configGeneration && !stopping && active
        if (!alive()) return@launch
        val service = systemService("window", "android.view.IWindowManager") // binder on IO
        val type = Class.forName("android.view.IWindowManager")
        val rotation = displayRotationFor()
        if (!skipWindowing) {
            desktopModeConfigurator.configureDisplay(
                targetId, forceFreeformSession || (isXiaomiProfile && xiaomiFlags.forceFreeform)
            )
        }
        if (gen != configGeneration || stopping) return@launch
        runCatching {
            type.getMethod("setIgnoreOrientationRequest", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).invoke(service, targetId, true)
        }.onSuccess { OperationLog.i(this@MirrorService, "Orientation", "ignore orientation request applied display=$targetId") }
         .onFailure { OperationLog.e(this@MirrorService, "Orientation", "orientation request lock failed display=$targetId", it) }

        if (gen != configGeneration || stopping) return@launch
        runCatching {
            type.getMethod("setFixedToUserRotation", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(service, targetId, 2)
        }.onSuccess { OperationLog.i(this@MirrorService, "Orientation", "fixed rotation applied display=$targetId value=2") }
         .onFailure { OperationLog.e(this@MirrorService, "Orientation", "fixed rotation failed display=$targetId", it) }

        applyDisplayRotation(rotation, service, type, targetId, gen)
        if (gen != configGeneration || stopping) return@launch
        runCatching {
            type.getMethod("setShouldShowSystemDecors", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).invoke(service, targetId, decorations)
        }
        if (gen != configGeneration || stopping) return@launch
        runCatching {
            type.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).invoke(service, targetId, if (autoOnly) 1 else 0)
        }
        Log.i(logTag, "Dextop display configured display=$targetId rotation=$rotation")
    }
}
```
Note `type`/`service` are now obtained once on IO, and `targetId`/`decorations`/`autoOnly` are snapshotted before launch so a concurrent stop cannot retarget the calls.

Replace `applyDisplayRotation()` `:12793-12825` (keep a defaulted overload for the `:10145` caller; that caller is itself still main-side and should later be dispatched to IO too):
```kotlin
private fun applyDisplayRotation(
    rotation: Int,
    service: Any? = null,
    type: Class<*>? = null,
    displayId: Int = targetDisplayId,
    gen: Long = configGeneration
) {
    if (displayId < 0 || gen != configGeneration || stopping) return
    val windowService = service ?: systemService("window", "android.view.IWindowManager")
    val windowType = type ?: Class.forName("android.view.IWindowManager")
    runCatching {
        val method = windowType.methods.first {
            it.name == "freezeDisplayRotation" && it.parameterTypes.size >= 2
        }
        val args = method.parameterTypes.mapIndexed { index, parameter ->
            when {
                index == 0 -> displayId
                index == 1 -> rotation
                parameter == String::class.java -> packageName
                parameter == Boolean::class.javaPrimitiveType -> true
                else -> null
            }
        }.toTypedArray()
        method.invoke(windowService, *args)
    }.onSuccess {
        OperationLog.i(this, "Orientation", "display rotation lock applied display=$displayId rotation=$rotation")
    }.onFailure {
        OperationLog.e(this, "Orientation", "display rotation lock failed display=$displayId", it)
    }
}
```

### Patch 4 — chain `applyForCurrentDevice()` continuations (ordering preserved)

`createDisplay()` `:12136-12150` — replace the inline `applyForCurrentDevice(); clearOverlay...; root?.postDelayed{...}` with:
```kotlin
runCatching {
    val existing = displayBackend.currentDisplayIds()
    val staleOverlays = displayBackend.overlayDisplayIds()
    val gen = configGeneration
    desktopModeConfigurator.applyForCurrentDevice().invokeOnCompletion {
        mainHandler.post {
            if (gen != configGeneration || stopping || !active) return@post
            clearOverlayDisplayRequestTwice("before_display_create")
            mainHandler.postDelayed({
                if (gen != configGeneration || stopping || !active) return@postDelayed
                waitForOverlayRequestCleared(existing, staleOverlays, width, height, 0, showSystemDecorations)
            }, 150L)
        }
    }
}
```
Apply the same `invokeOnCompletion { mainHandler.post { ... } }` wrapper at `createDirectPhoneDisplay():12179` (continuation: `clearOverlayDisplayRequestTwice("before_direct_phone_display_create")` + `platform.openOwned(...)`) and `createHeadlessDisplay():12241` (continuation: `clearOverlayDisplayRequestTwice("before_hidden_auto_display_create")` + `createDirectAutoDisplay(...)`). This keeps the global writes ahead of the overlay request while moving the binder/fsync off main.

### Patch 5 — `finishStop()` `:14014-14025`: restore after cancelled jobs

```kotlin
if (autoSessionActive || coverSessionActive) {
    restoreDeferred.complete(true)
} else {
    desktopModeConfigurator.restore { ok -> restoreDeferred.complete(ok) }
}
restoreDeferred.invokeOnCompletion {
    mainHandler.post {
        val restored = restoreDeferred.getCompleted()
        if (restored && !autoSessionActive && !coverSessionActive) sessionJournal.clear()
        if (restored) { /* existing cleanup_pending/verified_at block :14026-14033 */ }
        if (wasActive) OperationLog.finishSession(this, restored)
        stopping = false
        finishCarPan()
        autoOnlySession = false
    }
}
```
Apply the same callback conversion to the other two call sites: `:8375` (`if (!autoSessionActive && !coverSessionActive) desktopModeConfigurator.restore()`) and `:11339` (lock-screen suspend path). Because `restore()` now owns the `sessionJournal.restoreSystemSettings()` call, drop the duplicate at `:14022`.

## Risks / device testing needed
- StrictMode penaltyLog + Perfetto on Redmi Pad Pro 5G / HyperOS 3: assert no `fd.sync()` / WMS binder call on the main thread during `surfaceCreated`/`attachDesktopDisplay`; assert `configureDisplay` reflection runs on `DefaultDispatcher-worker`.
- `kill -9` mid-`applyForCurrentDevice`: generation guard must prevent a post-restore write; verify all 7 globals still restore.
- Stop during `waitForOverlay` retry and during an in-flight `wm` command: no late `attachDesktopDisplay()`, no windowing override after restore (the `quiescence.join()` barrier).
- Verify `Handler.removeCallbacksAndMessages(null)` does not drop unrelated retained callbacks — `mainHandler` must be used only by `postMainDelayed` and the new restore/callback paths; the remaining ad-hoc `Handler(mainLooper).postDelayed` sites (`:1896,1951,1964,1980,2005,11368,11385,11452,13964,…`) are out of scope and should be migrated in a follow-up wave.
- `applyDisplayRotation(0)` at `:10145` is still main-side hidden IPC; migrate to `sessionIoScope.launch` in a follow-up.
