# Code Agent W6B (M3) — Wave 6 Report

## Scope inspected
- `android/app/src/main/kotlin/moe/n4tsu/dextop/MainActivity.kt:358-848` — single `MethodChannel` (`app.freedextop/display`) `when(call.method)` dispatch, 80 branches incl. `displayPhysicalDensity` (grouped at :608).
- `MainActivity.kt:1217-1224` (`startDisplay` args), `:1401-1426` (`launchApp` args), `:1268-1276` (`stopDisplay`).
- `AppCatalog.kt:15,33`; `MirrorService.kt:574,599`; `DisplayTopologyController.kt`; `DisplayEnvironmentSettings.kt`; `SamsungDesktopSettings.kt`; `plasma/BookStore.kt`; `LaptopKeyboardThemeRepository.kt`.
- `.agents/skills/dextop-flutter-to-compose-parity/SKILL.md:10` — 166-RPC contract. This report owns **DisplayAndShellRepository (33) + WorkspaceAndAppsRepository (5) = 38**. Remaining 42 MainActivity methods belong to Session(16)/Privilege(11)/Diagnostics(11) + 4 boundary methods (`requestStoreNotificationPermission`, `openUrl`, `performanceHud`, `keepAwake`) assigned to those sibling repos; no name is dropped.

## Findings (P1/P2)
- **P1** — `MainActivity.kt:358-848` mixes IO (`Thread{}.start()` :513-660, :750-825), main-looper IPC (`:487,525,602`), and callback-to-native completion (`:462,583,604`) inside one `when`. Direct port to Compose would reintroduce BUG-02 (blocking `wm` IPC on main). Fix: repositories suspend on `Dispatchers.IO`; adapter wraps callbacks in `suspendCancellableCoroutine` with job-scoped cancellation.
- **P1** — Error codes are part of the wire contract (`INVALID_DISPLAY_MODE`, `BOOK_SETTING`, `DISPLAY_ENVIRONMENT_WRITE`, `VIRTUAL_POINTER_PROFILE_WRITE`, `DISPLAY_TOPOLOGY_WRITE`, `display_setting`, `DISPLAY_MODE_WRITE`, `SAMSUNG_SETTINGS_{READ,WRITE,BACKUP,RESTORE}`, `app`, `display`). They must survive as typed sealed errors, not strings lost in `Result.failure`.
- **P2** — State reads (`externalDisplayState`, `displayTopology`, `bookSettings`, `samsungDesktopSettings`) are pull-based callbacks today; Compose needs push-based `StateFlow`. Repositories refresh flows after every successful write; a `refresh()` re-reads all getters.

## Deliverable

### 1. RPC contract (method name + args — byte-identical to MainActivity)

**DisplayAndShellRepository — 33 methods**

| # | method | args (exact keys/types) | result |
|---|---|---|---|
| 1 | `showOverlayDemo` | — | `Boolean` (true) |
| 2 | `hideOverlayDemo` | — | `Unit` |
| 3 | `foldableLaptopMode` | `enabled: Boolean?` | `Unit` |
| 4 | `laptopKeyboardTheme` | `themeId: String?` | `Unit`; err `invalid_theme` |
| 5 | `laptopSwipeLanguagesChanged` | `enabled: Boolean?` | `Unit` |
| 6 | `previewLaptopOverlay` | `themeId: String?` | `Boolean` |
| 7 | `hideLaptopDemo` | — | `Unit` |
| 8 | `exitLaptopPreview` | — | `Unit` |
| 9 | `hideOverlayForSettings` | — | `Unit` |
| 10 | `restoreOverlayAfterSettings` | — | `Unit` |
| 11 | `isFoldableDevice` | — | `Boolean` |
| 12 | `setWindowManager` | — | `Unit` |
| 13 | `externalDisplayState` | — | `Map<String,Any?>` |
| 14 | `setExternalDisplay` | `mode: String?` (`off\|external\|mirror\|extend`, default `off`) | `String`; err `INVALID_DISPLAY_MODE` |
| 15 | `plasmaSettingsChanged` | — | `Unit` |
| 16 | `bookSettings` | — | `Map<String,Any?>` |
| 17 | `setBookSetting` | `key: String?`, `value: Int?` if `key=="display_scale"` else `Boolean?` | `Map<String,Any?>`; err `BOOK_SETTING` |
| 18 | `currentDeviceDisplayProfile` | — | `{width:Int,height:Int,density:Int}` |
| 19 | `samsungDesktopSettings` | — | `Map<String,Any?>`; err `SAMSUNG_SETTINGS_READ` |
| 20 | `displayTopology` | — | `Map<String,Any?>` |
| 21 | `displayModeDisplays` | — | `Map<String,Any?>` |
| 22 | `displayEnvironmentSettings` | — | `Map<String,Any?>` |
| 23 | `setDisplayEnvironmentSetting` | `id: String?`, `enabled: Boolean?` | `Map<String,Any?>`; err `DISPLAY_ENVIRONMENT_WRITE` |
| 24 | `setVirtualPointerProfile` | `profile: String?` (`touchpad\|mouse\|software`, lowercased) | `{virtualPointerProfile:String}`; err `VIRTUAL_POINTER_PROFILE_WRITE` |
| 25 | `setDisplayTopology` | `positions: Map<*,*>?` | `Map<String,Any?>`; err `DISPLAY_TOPOLOGY_WRITE` |
| 26 | `setExtendedDisplayScale` | `displayId:Int?` (−1), `percent:Int?` (100) | `Unit`; err `display_setting` |
| 27 | `setDisplayDensity` | `displayId:Int?`, `dpi:Int?` (0) | `Any`; err `display_setting` |
| 28 | `setDisplayRotation` | `displayId:Int?`, `rotation:Int?` (0) | `Any`; err `display_setting` |
| 29 | `displayPhysicalDensity` | `displayId:Int?` | `Any`; err `display_setting` |
| 30 | `setDisplayPreferredMode` | `displayId:Int?`, `width:Int?`, `height:Int?`, `refreshRate:Number?`→Float | `Map<String,Any?>`; err `DISPLAY_MODE_WRITE` |
| 31 | `setSamsungDesktopSetting` | `id: String?`, `value: Any?` | `Map<String,Any?>`; err `SAMSUNG_SETTINGS_WRITE` |
| 32 | `backupSamsungDesktopSettings` | — | `Any`; err `SAMSUNG_SETTINGS_BACKUP` |
| 33 | `restoreSamsungDesktopSettings` | — | `Any`; err `SAMSUNG_SETTINGS_RESTORE` |

**WorkspaceAndAppsRepository — 5 methods**

| # | method | args | result |
|---|---|---|---|
| 1 | `apps` | — | `List<Map<String,Any>>` (`AppCatalog.launchableApps()`) |
| 2 | `appsMetadata` | — | `List<Map<String,Any>>` (`includeIcons=false`) |
| 3 | `appIcons` | — | `Map<String,ByteArray>` |
| 4 | `launchApp` | `package:String?`, `bounds:List<Int>?` (size 4 → `Rect`), `position:String?` | `Unit`; err `app`, `display` |
| 5 | `launchAutoWorkspace` | `id: String?` | `Boolean` |

### 2. Shared transport adapter

```kotlin
// repository/MethodChannelAdapter.kt — ONLY place that knows MethodChannel.
interface MethodChannelAdapter {
    suspend fun <T> call(method: String, args: Map<String, Any?> = emptyMap()): T
}

sealed interface RpcError(val code: String, override val message: String?) : Exception() {
    data class InvalidDisplayMode(override val message: String?) : RpcError("INVALID_DISPLAY_MODE", message)
    data class BookSetting(override val message: String?) : RpcError("BOOK_SETTING", message)
    data class DisplayEnvironmentWrite(override val message: String?) : RpcError("DISPLAY_ENVIRONMENT_WRITE", message)
    data class VirtualPointer(override val message: String?) : RpcError("VIRTUAL_POINTER_PROFILE_WRITE", message)
    data class TopologyWrite(override val message: String?) : RpcError("DISPLAY_TOPOLOGY_WRITE", message)
    data class DisplaySetting(override val message: String?) : RpcError("display_setting", message)
    data class DisplayModeWrite(override val message: String?) : RpcError("DISPLAY_MODE_WRITE", message)
    data class Samsung(val op: String, override val message: String?) : RpcError("SAMSUNG_SETTINGS_$op", message)
    data class App(override val message: String?) : RpcError("app", message)
    data class Display(override val message: String?) : RpcError("display", message)
    data class InvalidTheme(override val message: String?) : RpcError("invalid_theme", message)
    data class Unknown(val rawCode: String, override val message: String?) : RpcError(rawCode, message)
}

class FlutterMethodChannelAdapter(
    private val channel: MethodChannel,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MethodChannelAdapter {
    override suspend fun <T> call(method: String, args: Map<String, Any?>) = withContext(io) {
        suspendCancellableCoroutine<T> { cont ->
            channel.invokeMethod(method, args, object : MethodChannel.Result {
                override fun success(r: Any?) { if (cont.isActive) cont.resume(r as T) }
                override fun error(code: String, msg: String?, details: Any?) {
                    if (cont.isActive) cont.resumeWith(Result.failure(mapError(code, msg)))
                }
                override fun notImplemented() {
                    if (cont.isActive) cont.resumeWith(Result.failure(RpcError.Unknown("notImplemented", method)))
                }
            })
            cont.invokeOnCancellation { /* native callbacks are one-shot; ignore late result */ }
        }
    }
    private fun mapError(code: String, msg: String?) = when (code) { /* exact code→type map above */ ... else -> RpcError.Unknown(code, msg) }
}
```

### 3. DisplayAndShellRepository (typed requests/results + StateFlow)

```kotlin
data class DisplayState(
    val external: Map<String, Any?> = emptyMap(),
    val topology: Map<String, Any?> = emptyMap(),
    val bookAppearance: Map<String, Any?> = emptyMap(),
    val samsungSettings: Map<String, Any?> = emptyMap(),
    val foldable: Boolean = false,
    val virtualPointerProfile: String = "software",
    val version: Long = 0, // bumped on every successful mutation
)

sealed interface DisplayRequest {
    val method: String
    fun args(): Map<String, Any?>

    data object ShowOverlayDemo : DisplayRequest { override val method = "showOverlayDemo"; override fun args() = emptyMap<String, Any?>() }
    data object HideOverlayDemo : DisplayRequest { /* "hideOverlayDemo" */ }
    data class FoldableLaptopMode(val enabled: Boolean?) : DisplayRequest { /* "foldableLaptopMode"; args {"enabled":enabled} */ }
    data class LaptopKeyboardTheme(val themeId: String?) : DisplayRequest { /* "laptopKeyboardTheme"; {"themeId":themeId} */ }
    data class LaptopSwipeLanguagesChanged(val enabled: Boolean?) : DisplayRequest
    data class PreviewLaptopOverlay(val themeId: String?) : DisplayRequest
    data object HideLaptopDemo : DisplayRequest
    data object ExitLaptopPreview : DisplayRequest
    data object HideOverlayForSettings : DisplayRequest
    data object RestoreOverlayAfterSettings : DisplayRequest
    data object IsFoldableDevice : DisplayRequest
    data object SetWindowManager : DisplayRequest
    data object ExternalDisplayState : DisplayRequest
    data class SetExternalDisplay(val mode: String?) : DisplayRequest   // validate off|external|mirror|extend
    data object PlasmaSettingsChanged : DisplayRequest
    data object BookSettings : DisplayRequest
    data class SetBookSetting(val key: String?, val value: Any?) : DisplayRequest // display_scale:Int else Boolean
    data object CurrentDeviceDisplayProfile : DisplayRequest
    data object SamsungDesktopSettings : DisplayRequest
    data object DisplayTopology : DisplayRequest
    data object DisplayModeDisplays : DisplayRequest
    data object DisplayEnvironmentSettings : DisplayRequest
    data class SetDisplayEnvironmentSetting(val id: String?, val enabled: Boolean?) : DisplayRequest
    data class SetVirtualPointerProfile(val profile: String?) : DisplayRequest // lowercase + validate
    data class SetDisplayTopology(val positions: Map<*, *>?) : DisplayRequest
    data class SetExtendedDisplayScale(val displayId: Int? = -1, val percent: Int? = 100) : DisplayRequest
    data class SetDisplayDensity(val displayId: Int?, val dpi: Int? = 0) : DisplayRequest
    data class SetDisplayRotation(val displayId: Int?, val rotation: Int? = 0) : DisplayRequest
    data class DisplayPhysicalDensity(val displayId: Int?) : DisplayRequest
    data class SetDisplayPreferredMode(val displayId: Int?, val width: Int?, val height: Int?, val refreshRate: Number?) : DisplayRequest
    data class SetSamsungDesktopSetting(val id: String?, val value: Any?) : DisplayRequest
    data object BackupSamsungDesktopSettings : DisplayRequest
    data object RestoreSamsungDesktopSettings : DisplayRequest
}

sealed interface DisplayResult {
    data object Unit : DisplayResult
    data class Bool(val value: Boolean) : DisplayResult
    data class Text(val value: String) : DisplayResult
    data class AnyValue(val value: Any?) : DisplayResult
    data class MapValue(val value: Map<String, Any?>) : DisplayResult
    data class Profile(val width: Int, val height: Int, val density: Int) : DisplayResult
    data class PointerProfile(val value: String) : DisplayResult
}

@Singleton class DisplayAndShellRepository @Inject constructor(
    private val channel: MethodChannelAdapter,
) {
    private val _state = MutableStateFlow(DisplayState())
    val state: StateFlow<DisplayState> = _state.asStateFlow()

    suspend fun <T : DisplayResult> execute(request: DisplayRequest): T {
        val raw: Any? = channel.call(request.method, request.args())
        val typed = decode(request, raw)
        applyToState(request, typed)          // push-based StateFlow, no UI reads
        return typed as T
    }

    suspend fun refresh() { /* re-call ExternalDisplayState, DisplayTopology, BookSettings, SamsungDesktopSettings, IsFoldableDevice; update _state atomically */ }

    private fun decode(req: DisplayRequest, raw: Any?): DisplayResult = when (req) {
        is DisplayRequest.ShowOverlayDemo, is DisplayRequest.PreviewLaptopOverlay -> DisplayResult.Bool(raw == true)
        is DisplayRequest.IsFoldableDevice -> DisplayResult.Bool(raw == true)
        is DisplayRequest.SetExternalDisplay -> DisplayResult.Text(raw as? String ?: "ok")
        is DisplayRequest.CurrentDeviceDisplayProfile -> raw.asMap().let {
            DisplayResult.Profile(it["width"] as Int, it["height"] as Int, it["density"] as Int)
        }
        is DisplayRequest.SetVirtualPointerProfile -> DisplayResult.PointerProfile(raw.asMap()["virtualPointerProfile"] as String)
        is DisplayRequest.SetDisplayDensity, is DisplayRequest.SetDisplayRotation,
        is DisplayRequest.DisplayPhysicalDensity,
        is DisplayRequest.BackupSamsungDesktopSettings,
        is DisplayRequest.RestoreSamsungDesktopSettings -> DisplayResult.AnyValue(raw)
        else -> if (raw is Map<*, *>) DisplayResult.MapValue(raw.asMap()) else DisplayResult.Unit
    }

    private fun applyToState(req: DisplayRequest, result: DisplayResult) {
        _state.update { s ->
            val next = when (req) {
                is DisplayRequest.ExternalDisplayState -> s.copy(external = (result as DisplayResult.MapValue).value)
                is DisplayRequest.DisplayTopology, is DisplayRequest.SetDisplayTopology ->
                    s.copy(topology = (result as DisplayResult.MapValue).value)
                is DisplayRequest.BookSettings, is DisplayRequest.SetBookSetting ->
                    s.copy(bookAppearance = (result as DisplayResult.MapValue).value)
                is DisplayRequest.SamsungDesktopSettings, is DisplayRequest.SetSamsungDesktopSetting ->
                    s.copy(samsungSettings = (result as DisplayResult.MapValue).value)
                is DisplayRequest.IsFoldableDevice -> s.copy(foldable = (result as DisplayResult.Bool).value)
                is DisplayRequest.SetVirtualPointerProfile -> s.copy(virtualPointerProfile = (result as DisplayResult.PointerProfile).value)
                else -> s
            }
            next.copy(version = s.version + 1)
        }
    }
}
```

### 4. WorkspaceAndAppsRepository (5 methods)

```kotlin
data class WorkspaceState(
    val apps: List<Map<String, Any>> = emptyList(),
    val appMetadata: List<Map<String, Any>> = emptyList(),
    val icons: Map<String, ByteArray> = emptyMap(),
    val version: Long = 0,
)

sealed interface WorkspaceRequest {
    val method: String
    fun args(): Map<String, Any?>
    data object Apps : WorkspaceRequest                 // "apps"
    data object AppsMetadata : WorkspaceRequest         // "appsMetadata"
    data object AppIcons : WorkspaceRequest             // "appIcons"
    data class LaunchApp(val packageName: String?, val bounds: List<Int>? = null, val position: String? = null) : WorkspaceRequest
    data class LaunchAutoWorkspace(val id: String?) : WorkspaceRequest
}

sealed interface WorkspaceResult {
    data class Apps(val value: List<Map<String, Any>>) : WorkspaceResult
    data class Icons(val value: Map<String, ByteArray>) : WorkspaceResult
    data object Launched : WorkspaceResult
    data class AutoWorkspace(val value: Boolean) : WorkspaceResult
}

@Singleton class WorkspaceAndAppsRepository @Inject constructor(private val channel: MethodChannelAdapter) {
    private val _state = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    suspend fun execute(req: WorkspaceRequest): WorkspaceResult {
        val raw = channel.call<Any?>(req.method, req.args())
        return when (req) {
            is WorkspaceRequest.Apps -> WorkspaceResult.Apps(raw.castList()).also { _state.update { s -> s.copy(apps = it.value, version = s.version + 1) } }
            is WorkspaceRequest.AppsMetadata -> WorkspaceResult.Apps(raw.castList()).also { _state.update { s -> s.copy(appMetadata = it.value, version = s.version + 1) } }
            is WorkspaceRequest.AppIcons -> WorkspaceResult.Icons(raw.castIcons()).also { _state.update { s -> s.copy(icons = it.value, version = s.version + 1) } }
            is WorkspaceRequest.LaunchApp -> WorkspaceResult.Launched
            is WorkspaceRequest.LaunchAutoWorkspace -> WorkspaceResult.AutoWorkspace(raw == true)
        }
    }
}
```

### 5. MethodChannel adapter delegation in MainActivity (M3 end state)
Move the `when` body behind a thin `RepositoryMethodChannelHandler` that only *routes*:
```kotlin
flutterChannel?.setMethodCallHandler { call, result ->
    scope.launch {
        runCatching { router.dispatch(call.method, call.arguments as? Map<*, *>) }
            .onSuccess { withContext(Dispatchers.Main) { result.success(it) } }
            .onFailure { e ->
                withContext(Dispatchers.Main) {
                    val (code, msg) = (e as? RpcError)?.let { it.code to it.message }
                        ?: "internal" to e.message
                    result.error(code, msg, null)
                }
            }
    }
}
// router: displayRepo.methods + workspaceRepo.methods + session/privilege/diag repos;
// unknown -> MethodChannel.Result.notImplemented() (preserve current else branch).
```
`Dispatcher.IO` in adapter keeps `wm`/`settings` IPC off main looper (BUG-02 fix); `withContext(Main)` only for `result.*` delivery, matching current `runOnUiThread` behavior.

## Test plan
1. **Contract parity (unit, JVM):** golden list of the 33 + 5 method names (copied from `MainActivity:358-848`) vs `DisplayRequest.method` / `WorkspaceRequest.method` values — fail on any rename/omit. Assert `args()` key sets equal the `call.argument<...>("key")` literals in source.
2. **Arg-shape tests (fake adapter):** a `RecordingAdapter` captures `(method, args)` for all 38 requests; assert defaults (`mode="off"`, `displayId=-1`, `percent=100`, `dpi=0`, `rotation=0`, `level/tag` n/a), `refreshRate:Number`→Float, `bounds` list-of-4, `positions` map passthrough, `value` typed Int vs Boolean for `setBookSetting`.
3. **Error mapping:** feed each of the 13 error codes through `FlutterMethodChannelAdapter` → assert exact `RpcError` subtype + preserved `code` so Dart sees identical strings.
4. **StateFlow (Turbine):** `refresh()` emits full snapshot; each write updates `version` once; failed write leaves state unchanged; concurrent writes atomic via `MutableStateFlow.update`.
5. **Cancellation:** cancel a `setDisplayTopology` job before native callback → no resume, no crash (guards BUG-02 pattern).
6. **Instrumented (device, CI only):** Xiaomi Redmi Pad Pro 5G / API 36 / HyperOS 3 — walk `setExternalDisplay` 4 modes, `setDisplayDensity/Rotation/PreferredMode` + `clearRecovery`-style WAL restore, `setVirtualPointerProfile` 3 values, `displayTopology` rearrange, `launchApp` with bounds & position, `appIcons` byte size. Graceful degradation on `samsungDesktopSettings` (non-Samsung) → `{}` + no crash.
7. **Exit gate:** 38/38 RPC tests green; `MethodChannel` references confined to adapter + handler; no `GlobalScope`; Compose screens use `collectAsStateWithLifecycle`.

## Risks / device testing needed
- Hidden-API display writes (`DisplayTopologyController`, `setDisplayPreferredMode`) may throw on HyperOS 3; adapter must map to `DISPLAY_MODE_WRITE`/`display_setting` exactly as today and never crash the channel.
- Callback-based native methods are one-shot: a late callback after coroutine cancellation could resume a dead continuation — guarded by `isActive` but verify under rapid start/stop.
- `appIcons` returns `ByteArray` through MethodChannel → large payload; keep on IO dispatcher, ensure no main-looper serialization.
- Partition boundary: 4 methods (`requestStoreNotificationPermission`, `openUrl`, `performanceHud`, `keepAwake`) are intentionally outside these two repos; sibling W6 agents must claim them or the 166-RPC checklist breaks.
