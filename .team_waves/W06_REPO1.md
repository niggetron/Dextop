# W6A (M3) — Wave 06 Report: SessionRepository + PrivilegeRepository + MethodChannel adapter

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/MainActivity.kt:358-848` — `MethodChannel.setMethodCallHandler` (79 distinct method names).
- Helpers: `status()` :909-1014, `requestShizuku()` :1016-1057, `openShizuku()` :1059-1083, provider discovery/selection :1085-1203, `startDisplay()` :1205-1266, `stopDisplay()` :1268-1276, `discardRecovery()` :1278-1312, `repairState()` :1314-1357, `repairAndroid()` :1359-1388, `restartApp()` :1390-1399, `openSettings()` :1466-1474, notification/pairing :1496-1538, `openSystemWirelessDebuggingSettings()` :1617-1638, permission result :1696-1725, timeouts :112-125, notify :230.
- Counts contract: `.agents/skills/dextop-flutter-to-compose-parity/SKILL.md:10` — SessionRepository 16, PrivilegeRepository 11 (27 of 166).
- Style refs: `ResolutionRepository.kt`, `LaptopKeyboardThemeRepository.kt` (internal classes, `Context`-backed prefs). `W04_FSM.md:30-100` (`DesktopSessionFsm`, `SessionConfig`, `FsmState`, `StateFlow`).
- Dart callers `lib/app_shell.dart:228-230,376`, `lib/home_screen.dart:851`, `lib/features_page.dart:741`, `lib/app_info.dart:739` (one-shot intents, HUD/awake flags).

## Findings
- **P1 — wire contract is untyped and error-code-only.** Every branch builds `Map<String,Any?>` and calls `result.error(code, msg, null)`. Preserve the literal codes and map keys exactly or the existing Dart screens break before Compose ships. Golden list of codes captured in the adapter below.
- **P1 — `MethodChannel.Result` is held across main-looper `Handler` callbacks** (`pendingStartResult`/`pendingPermissionResult`, :108-125). Two results can be in flight; timeouts must remain cancellable and mutually exclusive. Replace Handlers with `withTimeout` + a single `Mutex`/state guard, not `GlobalScope`.
- **P1 — side effects require an Activity** (`startActivity`, `requestPermissions`, `requestedOrientation`, `finish`, one-shot `intent.action = null`). Repositories must not own `Activity`; inject a thin `ActivityHost` interface or unit tests leak against the framework.
- **P2 — blocking work on the calling thread.** `installedPrivilegeProviders()` scans `getInstalledPackages(GET_PERMISSIONS)`; `status()` does Settings/package queries; `recovery()` reads the WAL. Move to `Dispatchers.IO` behind suspend methods; keep result delivery on main.
- **P2 — `kotlinx-coroutines` is only transitive** (`W04_FSM.md:14`); declare `kotlinx-coroutines-android` + `-test` explicitly before these files land.
- **P3 — `localText()`/`NativeStrings` need `Context`.** Inject `TextProvider` so error messages are testable without resources.
- **Boundary decision (27 exactly):** Session = `sessionState, launchContext, start, stop, stopAuto, launchAutoWorkspace, recovery, clearRecovery, repairState, repairAndroid, restartApp, consumeTileAction, consumeShortcutAction, finishActivity, keepAwake, performanceHud`. Privilege = `status, requestShizuku, openShizuku, embeddedPrivilegeInfo, pairEmbeddedPrivilege, startEmbeddedPrivilege, selectPrivilegeProvider, requestEmbeddedNotificationPermission, requestStoreNotificationPermission, prepareEmbeddedPairingNotification, openAccessibility`. `openWirelessDebugging*` stay with the DisplayAndShell wave (system-settings navigation); if the master blueprint assigns them to Privilege, swap them with `openAccessibility` — counts remain 16/11 and only the route table changes.

## Deliverable

### `app/src/main/kotlin/moe/n4tsu/dextop/repo/RepoContracts.kt`

```kotlin
package moe.n4tsu.dextop.repo

import kotlinx.coroutines.flow.StateFlow

/** Wire-faithful failure: `code` is the existing MethodChannel error code. */
internal sealed interface RepoFailure {
    val code: String
    val message: String?
}

internal typealias RepoResult<T> = Result<T>

/** Testable substitutes for Activity-only side effects. Main thread only. */
internal interface ActivityHost {
    fun startActivity(action: String, url: String? = null, packageId: String? = null): Boolean
    fun requestNotifications(): Boolean          // true = request issued
    fun requestShizukuPermission(): Boolean
    fun setOrientation(portrait: Boolean)
    fun restoreOrientation()
    fun finishActivity()
    fun restartApp(delayMs: Long = 120)
    fun consumeTileAction(): Boolean             // reads then clears intent.action
    fun consumeShortcutAction(): ShortcutAction? // reads then clears intent.action
}

internal interface TextProvider { fun get(key: String): String }

internal data class ShortcutAction(val closeAfterStart: Boolean)

internal data class StartRequest(
    val width: Int = 1920, val height: Int = 1080, val density: Int = 240,
    val secure: Boolean = false, val decorations: Boolean? = null,
)
```

### `repo/SessionRepository.kt` — 16 typed methods, StateFlow state

```kotlin
package moe.n4tsu.dextop.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import moe.n4tsu.dextop.session.DesktopSessionFsm
import moe.n4tsu.dextop.session.FsmState

internal sealed interface SessionFailure : RepoFailure {
    data class AutoSessionActive(override val message: String?) : SessionFailure {
        override val code = "auto_session_active"
    }
    data class ProfileOutOfRange(override val message: String?) : SessionFailure {
        override val code = "profile"
    }
    data class StartBusy(override val message: String?) : SessionFailure {
        override val code = "start_busy"
    }
    data class StartFailed(override val message: String?) : SessionFailure {
        override val code = "start_failed"
    }
    data object StartTimeout : SessionFailure {
        override val code = "start_timeout"; override val message get() = null
    }
    data class StartCancelled(override val message: String?) : SessionFailure {
        override val code = "start_cancelled"
    }
    data class ShizukuOffline(override val message: String?) : SessionFailure {
        override val code = "shizuku_offline"
    }
    data class Recovery(override val message: String?) : SessionFailure {
        override val code = "recovery"
    }
    data class Repair(override val message: String?) : SessionFailure {
        override val code = "repair"
    }
}

/** One snapshot for Compose; derived from FSM + MirrorService/Cardex flows (no Compose import). */
internal data class SessionSnapshot(
    val fsm: FsmState,
    val active: Boolean,
    val stopping: Boolean,
    val autoConnected: Boolean,
    val autoActive: Boolean,
    val keepAwake: Boolean,
    val performanceHud: Boolean,
    val recoveryAvailable: Boolean,
)

internal data class SessionStateView(
    val autoMode: String?, val active: Boolean, val stopping: Boolean,
    val autoConnected: Boolean, val autoActive: Boolean,
)
internal data class LaunchContext(
    val displayId: Int, val accentColor: Int?, val externalDisplay: Boolean,
    val desktopWindow: Boolean,
)
internal data class StartOutcome(val payload: Map<String, Any?>)
internal data class RepairState(
    val required: Boolean, val accessibilityResidual: Boolean, val displayResidual: Boolean,
    val cleanupPending: Boolean, val cardexInterrupted: Boolean, val pausedByUser: Boolean,
    val pausedSession: Boolean, val autoActive: Boolean, val phoneSession: Boolean,
)

/** `mirror` wraps MirrorService statics; `journal` wraps SessionJournal; `priv` is asked only for gate state. */
internal class SessionRepository(
    private val app: android.content.Context,
    private val fsm: DesktopSessionFsm,
    private val host: ActivityHost,
    private val mirror: MirrorGateway,
    private val journalFactory: () -> SessionJournalOps,
    private val priv: PrivilegeRepository,
    private val text: TextProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val state: StateFlow<SessionSnapshot> /* = combine(fsm.state, mirror.flows, priv.state) */

    suspend fun sessionState(): RepoResult<SessionStateView>              // :361-371
    suspend fun launchContext(): RepoResult<LaunchContext>                // :372-385
    suspend fun start(request: StartRequest): RepoResult<StartOutcome>    // :487,1205-1266
    suspend fun stop(): RepoResult<Unit>                                  // :507,1268-1276
    suspend fun stopAuto(): RepoResult<Unit>                              // :508-512
    suspend fun launchAutoWorkspace(id: String): RepoResult<Boolean>      // :526-532
    suspend fun recovery(): RepoResult<Map<String, Any?>>                 // :834
    suspend fun clearRecovery(): RepoResult<Unit>                         // :835,1278-1312
    suspend fun repairState(): RepoResult<RepairState>                    // :711,1314-1357
    suspend fun repairAndroid(): RepoResult<Unit>                         // :712,1359-1388
    suspend fun restartApp(): RepoResult<Unit>                            // :713,1390-1399
    suspend fun consumeTileAction(): RepoResult<Boolean>                  // :714-719 (one-shot)
    suspend fun consumeShortcutAction(): RepoResult<ShortcutAction?>      // :720-730 (one-shot)
    suspend fun finishActivity(): RepoResult<Unit>                        // :731-736
    suspend fun setKeepAwake(enabled: Boolean): RepoResult<Unit>          // :830-833
    suspend fun setPerformanceHud(enabled: Boolean): RepoResult<Unit>     // :826-829

    // Invariants to port verbatim:
    //  start: auto-session guard; width/height in 480..7680, density in 80..640;
    //  privilege gate via priv.canStartSession(); single in-flight start guard;
    //  host.setOrientation(height > width); 12_000 ms cancellable timeout;
    //  MirrorService.launch callback -> success(outcome map) | StartFailed.
    //  stop: cancel timeout, fail pending start with "start_cancelled", stopActive(),
    //  host.restoreOrientation().
}
```

### `repo/PrivilegeRepository.kt` — 11 typed methods, StateFlow state

```kotlin
package moe.n4tsu.dextop.repo

internal sealed interface PrivilegeFailure : RepoFailure {
    data class Grant(override val message: String?) : PrivilegeFailure { override val code = "grant" }
    data class Missing(override val message: String?) : PrivilegeFailure { override val code = "shizuku_missing" }
    data class Offline(override val message: String?) : PrivilegeFailure { override val code = "shizuku_offline" }
    data class Busy(override val message: String?) : PrivilegeFailure { override val code = "permission_busy" }
    data class Permission(override val message: String?) : PrivilegeFailure { override val code = "permission" }
    data class Timeout(override val message: String?) : PrivilegeFailure { override val code = "permission_timeout" }
    data class ProviderUnavailable(override val message: String?) : PrivilegeFailure { override val code = "provider_unavailable" }
    data class Shizuku(override val message: String?) : PrivilegeFailure { override val code = "shizuku" }
    data class NotificationBusy(override val message: String?) : PrivilegeFailure { override val code = "notification_permission_busy" }
    data class NotificationRequired(override val message: String?) : PrivilegeFailure { override val code = "notification_permission_required" }
    data class NotificationDenied(override val message: String?) : PrivilegeFailure { override val code = "notification_permission_denied" }
    data class Settings(override val message: String?) : PrivilegeFailure { override val code = "settings" }
    data class SettingsUnavailable(override val message: String?) : PrivilegeFailure { override val code = "settings_unavailable" }
    data class EmbeddedUnavailable(override val message: String?) : PrivilegeFailure { override val code = "embedded_unavailable" }
    data class EmbeddedPairing(override val message: String?) : PrivilegeFailure { override val code = "embedded_pairing" }
    data class EmbeddedStart(override val message: String?) : PrivilegeFailure { override val code = "embedded_start" }
}

internal data class PrivilegeProvider(val id: String, val name: String, val kind: String)
internal data class PrivilegeSnapshot(
    val providers: List<PrivilegeProvider>, val selectedId: String?, val provider: String,
    val granted: Boolean, val running: Boolean, val binderAlive: Boolean,
    val embeddedIncluded: Boolean, val embeddedSelected: Boolean, val embeddedPaired: Boolean,
    val pairingState: String?, val notificationGranted: Boolean,
    val setupRequired: Boolean, val externalAuthorizationRequired: Boolean,
)
internal data class EmbeddedInfo(val included: Boolean)
internal data class PairingResult(val paired: Boolean, val endpoint: String?, val detail: String?)
internal data class EmbeddedStart(val started: Boolean, val endpoint: String?, val detail: String?)

/** `status` returns the legacy map verbatim (same keys as :968-1012) for phase-1 parity. */
internal class PrivilegeRepository(
    private val app: android.content.Context,
    private val host: ActivityHost,
    private val text: TextProvider,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val state: StateFlow<PrivilegeSnapshot> // refreshed on Shizuku binder/permission listeners + selection

    suspend fun status(): RepoResult<Map<String, Any?>>                       // :360,909-1014
    suspend fun requestShizuku(): RepoResult<Boolean>                         // :386,1016-1057
    suspend fun openShizuku(): RepoResult<Unit>                               // :387,1059-1083
    suspend fun embeddedPrivilegeInfo(): RepoResult<EmbeddedInfo>             // :388-390
    suspend fun pairEmbeddedPrivilege(code: String): RepoResult<PairingResult>// :391-394,865-885
    suspend fun startEmbeddedPrivilege(): RepoResult<EmbeddedStart>           // :395,887-907
    suspend fun selectPrivilegeProvider(provider: String?): RepoResult<Map<String, Any?>> // :396,1190-1203
    suspend fun requestEmbeddedNotificationPermission(forStore: Boolean = false): RepoResult<Boolean> // :839-840,1496-1517
    suspend fun prepareEmbeddedPairingNotification(): RepoResult<Boolean>    // :841,1525-1538
    suspend fun openAccessibility(): RepoResult<Unit>                         // :836,1466-1474

    internal suspend fun canStartSession(): Boolean // binder + permission gate used by SessionRepository
}
```

### `repo/RepositoryMethodChannelAdapter.kt` — thin adapter, no business logic

```kotlin
package moe.n4tsu.dextop.repo

import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Owns only decode/encode/dispatch. MainActivity keeps the other ~52 branches until their waves land. */
internal class RepositoryMethodChannelAdapter(
    private val sessions: SessionRepository,
    private val privileges: PrivilegeRepository,
    private val scope: kotlinx.coroutines.CoroutineScope, // activity-scoped SupervisorJob + Main dispatcher
    private val text: TextProvider,
) {
    private val routes: Set<String> = SESSION_RPCS + PRIVILEGE_RPCS

    fun canHandle(method: String) = method in routes

    fun handle(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            runCatching {
                when (call.method) {
                    // Session (16)
                    "sessionState" -> sessions.sessionState().getOrThrow()
                    "launchContext" -> sessions.launchContext().getOrThrow()
                    "start" -> sessions.start(call.arguments<StartRequest>()).getOrThrow().payload
                    "stop" -> sessions.stop().getOrThrow()
                    "stopAuto" -> sessions.stopAuto().getOrThrow()
                    "launchAutoWorkspace" -> sessions.launchAutoWorkspace(call.arg<String>("id").orEmpty()).getOrThrow()
                    "recovery" -> sessions.recovery().getOrThrow()
                    "clearRecovery" -> sessions.clearRecovery().getOrThrow()
                    "repairState" -> sessions.repairState().getOrThrow()
                    "repairAndroid" -> sessions.repairAndroid().getOrThrow()
                    "restartApp" -> sessions.restartApp().getOrThrow()
                    "consumeTileAction" -> sessions.consumeTileAction().getOrThrow()
                    "consumeShortcutAction" -> sessions.consumeShortcutAction().getOrThrow()
                    "finishActivity" -> sessions.finishActivity().getOrThrow()
                    "keepAwake" -> sessions.setKeepAwake(call.arg<Boolean>("enabled") == true).getOrThrow()
                    "performanceHud" -> sessions.setPerformanceHud(call.arg<Boolean>("enabled") == true).getOrThrow()
                    // Privilege (11)
                    "status" -> privileges.status().getOrThrow()
                    "requestShizuku" -> privileges.requestShizuku().getOrThrow()
                    "openShizuku" -> privileges.openShizuku().getOrThrow()
                    "embeddedPrivilegeInfo" -> privileges.embeddedPrivilegeInfo().getOrThrow()
                    "pairEmbeddedPrivilege" -> privileges.pairEmbeddedPrivilege(call.arg<String>("code").orEmpty()).getOrThrow()
                    "startEmbeddedPrivilege" -> privileges.startEmbeddedPrivilege().getOrThrow()
                    "selectPrivilegeProvider" -> privileges.selectPrivilegeProvider(call.arg<String>("provider")).getOrThrow()
                    "requestEmbeddedNotificationPermission" -> privileges.requestEmbeddedNotificationPermission(false).getOrThrow()
                    "requestStoreNotificationPermission" -> privileges.requestEmbeddedNotificationPermission(true).getOrThrow()
                    "prepareEmbeddedPairingNotification" -> privileges.prepareEmbeddedPairingNotification().getOrThrow()
                    "openAccessibility" -> privileges.openAccessibility().getOrThrow()
                    else -> return@launch result.notImplemented()
                }
            }.onSuccess { result.success(it) }
             .onFailure { e -> result.error((e as? RepoFailure)?.code ?: "internal", (e as? RepoFailure)?.message ?: e.message, null) }
        }
    }

    companion object {
        val SESSION_RPCS = setOf("sessionState","launchContext","start","stop","stopAuto","launchAutoWorkspace",
            "recovery","clearRecovery","repairState","repairAndroid","restartApp","consumeTileAction",
            "consumeShortcutAction","finishActivity","keepAwake","performanceHud")
        val PRIVILEGE_RPCS = setOf("status","requestShizuku","openShizuku","embeddedPrivilegeInfo",
            "pairEmbeddedPrivilege","startEmbeddedPrivilege","selectPrivilegeProvider",
            "requestEmbeddedNotificationPermission","requestStoreNotificationPermission",
            "prepareEmbeddedPairingNotification","openAccessibility")
    }
}
```

`MainActivity.configureFlutterEngine` change (2 lines, no other logic moved):

```kotlin
val adapter = RepositoryMethodChannelAdapter(sessions, privileges, lifecycleScope, textProvider)
flutterChannel?.setMethodCallHandler { call, result ->
    if (adapter.canHandle(call.method)) adapter.handle(call, result)
    else when (call.method) { /* unchanged remaining ~52 branches */ else -> result.notImplemented() }
}
```

### Unit-test strategy
- **Golden parity test** (no Android): assert `SESSION_RPCS.size == 16`, `PRIVILEGE_RPCS.size == 11`, union == the 27 names above, and every literal MethodCallHandler branch in `MainActivity` is either in the union or in an explicit "remaining waves" list. This is the "no RPC dropped" guard.
- **Repository tests** (`local unit`, `StandardTestDispatcher`): fake `ActivityHost`, fake `MirrorGateway` (records `launch/stopActive` + emits scripted outcomes), real `DesktopSessionFsm` with effect recorder, in-memory `SessionJournalOps`/`SharedPreferences`, `FakePrivilegeGate`, `FakeTextProvider`. Use Turbine on `state`.
- **Timeout/busy tests**: `advanceTimeBy(12_000)` → `StartTimeout`; `advanceTimeBy(15_000)` → `PermissionTimeout`; assert a single terminal `Result` (no `success` after `error`), `stop()` cancels pending start with `start_cancelled`, and notification/permission busy guards are mutually exclusive.
- **Behavior-parity tests**: assert exact validation bounds (480..7680 / 80..640 → `profile`), `repairState()` flags against a scripted journal snapshot, `status()` map keys equal the golden key set from :968-1012, `consumeTileAction`/`consumeShortcutAction` are one-shot, `restartApp` fires once after 120 ms, and every `RepoFailure.code` equals the legacy string.
- **Adapter tests**: `FakeResult` captures success/error/notImplemented; assert decode defaults (width 1920/height 1080/density 240, `id.orEmpty()`, `code.orEmpty()`), that `requestStoreNotificationPermission` sets `forStore=true`, and no repository throws out of `handle`.
- **Robolectric** for provider discovery (`getInstalledPackages(GET_PERMISSIONS)` filtering/sort) and Settings/notification state; **instrumented device test** only for real Shizuku binder/pairing + HyperOS 3 accessibility writes.

## Risks / device testing needed
- Replacing main-looper `Handler` timeouts with `withTimeout` must keep the same wall-clock windows and "stopActive on start timeout" behavior; verify on a cold start on the Redmi Pad Pro 5G.
- `WRITE_SECURE_SETTINGS` grant and `ENABLED_ACCESSIBILITY_SERVICES` writes are HyperOS-sensitive; GitHub vs Play distribution branches (`grantSecureSettings()` :1731-1745) must stay identical.
- `status()` mutates `DistributionPrivilegeRuntime.enabled` as a side effect (`:917`); keep that write or Compose will show a stale provider.
- One-shot intent consumption must preserve the `intent.action = null` reset or a resume re-starts the session.
- Keep Flutter channel names/keys frozen until all screens pass parity; deleting `lib/` early breaks these RPCs.
- No builds run locally (brief constraint); CI must compile and run the unit/Robolectric suites.
