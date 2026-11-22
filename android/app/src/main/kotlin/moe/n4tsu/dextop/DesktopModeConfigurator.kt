package moe.n4tsu.dextop

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class StrategyAttempt(val strategy: String, val succeeded: Boolean, val detail: String)

/**
 * Applies and restores desktop-mode settings.
 *
 * Every blocking shell command now runs on a session-scoped IO coroutine instead
 * of a main-looper Handler (BUG-02). All delayed re-applies are cancellable and
 * are cancelled on stop/teardown, so a stale command cannot overwrite a freshly
 * restored windowing mode.
 */
internal class DesktopModeConfigurator(
    private val context: Context,
    private val resolver: ContentResolver,
    private val privilegedAccess: PrivilegedAccess,
    private val environment: DesktopEnvironment,
    private val sessionJournal: SessionJournal,
    // Test seams; production keeps the original five-argument call form.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandRunner: (Array<out String>) -> PrivilegedAccess.CommandResult =
        { args -> privilegedAccess.execute(*args) }
) {
    private data class SavedGlobal(val key: String, val value: String?)

    private val savedGlobals = Collections.synchronizedList(mutableListOf<SavedGlobal>())
    @Volatile private var applied = false
    @Volatile private var forcedFreeformGlobalsApplied = false

    private val scopeLock = Any()
    private val freeformGate = Any()
    @Volatile private var sessionScope: CoroutineScope = newSessionScope()
    private val displayJobs = ConcurrentHashMap<Int, Job>()

    private fun newSessionScope(): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun applyForCurrentDevice(): List<StrategyAttempt> {
        if (applied) return emptyList()
        applied = true
        if (environment.platformManaged) {
            OperationLog.i(context, "DesktopMode", "platform-managed environment=${environment.id}")
            return listOf(StrategyAttempt("platform_managed", true, environment.id))
        }
        return environment.temporaryGlobalSettings.map { (key, value) ->
            runCatching {
                val previous = Settings.Global.getString(resolver, key)
                savedGlobals += SavedGlobal(key, previous)
                // WAL: record the original value (including null = delete) before writing.
                sessionJournal.rememberGlobal(key, previous)
                check(Settings.Global.putString(resolver, key, value)) {
                    moe.n4tsu.dextop.localText("uiSettingRejected")
                }
                StrategyAttempt("global:$key", true, "$previous -> ${value ?: "<deleted>"}")
            }.getOrElse {
                OperationLog.w(context, "DesktopMode", "optional global setting failed key=$key", it)
                StrategyAttempt("global:$key", false, it.message.orEmpty())
            }
        }
    }

    /**
     * Optional configuration; failure is isolated to this display and never aborts startup.
     * All blocking `wm`/`cmd` IPC runs on the IO scope, never on the caller (main looper).
     * The returned Job is the cancellation unit; all current callers ignore it.
     */
    fun configureDisplay(displayId: Int, forceFreeform: Boolean = false): Job? {
        if (!environment.configureFreeformWindowing && !forceFreeform) return null
        // A newer request for the same display supersedes an older pending re-apply.
        displayJobs[displayId]?.cancel()
        val scope = synchronized(scopeLock) { sessionScope }
        val job = scope.launch {
            val attempts = configureDisplayNow(displayId, forceFreeform)
            if (attempts.any { it.strategy != "display_engagement" && it.succeeded }) {
                // Structured concurrency: both re-applies are children of this job.
                coroutineScope {
                    launch { reapplyWindowing(displayId, 250L) }
                    launch { reapplyWindowing(displayId, 750L) }
                }
            }
        }
        displayJobs[displayId] = job
        job.invokeOnCompletion { displayJobs.remove(displayId, job) }
        return job
    }

    private suspend fun configureDisplayNow(displayId: Int, forceFreeform: Boolean): List<StrategyAttempt> {
        val attempts = mutableListOf<StrategyAttempt>()
        if (forceFreeform && claimForcedFreeformGlobals()) {
            listOf("enable_freeform_support", "force_resizable_activities").forEach { key ->
                coroutineContext.ensureActive()
                val previous = Settings.Global.getString(resolver, key)
                savedGlobals += SavedGlobal(key, previous)
                sessionJournal.rememberGlobal(key, previous)
                val result = commandRunner(arrayOf("cmd", "settings", "put", "global", key, "1"))
                attempts += StrategyAttempt(
                    "global:$key", result.succeeded, result.error.ifBlank { result.output }
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 35) {
            coroutineContext.ensureActive()
            val engagement = commandRunner(
                arrayOf("wm", "set-display-engagement-mode", "-d", displayId.toString(), "3")
            )
            attempts += StrategyAttempt(
                "display_engagement", engagement.succeeded, engagement.error.ifBlank { engagement.output }
            )
        }
        val windowingStrategies = if (forceFreeform) {
            // Electron DEX order: cmd window before the older activity_task command.
            listOf("cmd_window", "wm")
        } else environment.windowingStrategies
        for (strategy in windowingStrategies) {
            coroutineContext.ensureActive()
            val args = when (strategy) {
                "cmd_window" -> arrayOf(
                    "cmd", "window", "set-display-windowing-mode", "-d", displayId.toString(), "5"
                )
                "wm" -> arrayOf("wm", "set-display-windowing-mode", "-d", displayId.toString(), "5")
                "activity_task_manager" -> arrayOf(
                    "cmd", "activity_task", "set-display-windowing-mode", displayId.toString(), "5"
                )
                else -> continue
            }
            val result = commandRunner(args)
            val attempt = StrategyAttempt(
                strategy, result.succeeded, result.error.ifBlank { result.output }
            )
            attempts += attempt
            OperationLog.i(
                context, "DesktopMode",
                "windowing display=$displayId strategy=$strategy success=${attempt.succeeded} detail=${attempt.detail}"
            )
            if (result.succeeded) break
        }
        return attempts
    }

    private suspend fun reapplyWindowing(displayId: Int, delayMs: Long) {
        delay(delayMs)
        coroutineContext.ensureActive()
        if (Build.VERSION.SDK_INT >= 35) {
            commandRunner(arrayOf("wm", "set-display-engagement-mode", "-d", displayId.toString(), "3"))
        }
        coroutineContext.ensureActive()
        val result = commandRunner(
            arrayOf("wm", "set-display-windowing-mode", "-d", displayId.toString(), "5")
        )
        OperationLog.i(
            context, "DesktopMode",
            "windowing reapply display=$displayId delay=$delayMs success=${result.succeeded} " +
                "detail=${result.error.ifBlank { result.output }}"
        )
    }

    private fun claimForcedFreeformGlobals(): Boolean = synchronized(freeformGate) {
        if (forcedFreeformGlobalsApplied) false else {
            forcedFreeformGlobalsApplied = true
            true
        }
    }

    /** Cancels in-flight and delayed work without restoring settings; synchronous, main-safe. */
    fun cancelPendingWork() {
        val oldScope = synchronized(scopeLock) {
            val previous = sessionScope
            sessionScope = newSessionScope()
            previous
        }
        oldScope.cancel()
        displayJobs.clear()
    }

    fun restore() {
        cancelPendingWork()
        sessionJournal.restoreSystemSettings()
        OperationLog.i(context, "DesktopMode", "restored settings count=${savedGlobals.size}")
        savedGlobals.clear()
        applied = false
        forcedFreeformGlobalsApplied = false
    }
}
