# W6C (M3) — Repositories / RPC-Parity Report

## Scope inspected
- `MainActivity.kt:358-848` — Flutter `app.freedextop/display` `MethodCallHandler`. Diagnostics slice = 11 method names: `diagnostics` :533, `lastSessionLog` :667, `deviceReportIdentity` :668, `sendDeviceReportEmail` :688, `metrics` :749, `diagnosticReport` :750, `clearDiagnosticLog` :755, `shareDiagnosticReport` :759, `logEvent` :770, `exportDiagnosticZip` :781, `shareDiagnosticZip` :800.
- Native targets (do NOT reimplement): `DeviceDiagnostics.report()/metrics()` :20/:155, `DiagnosticReport.build(Options)` :22, `DiagnosticExport.write(Objects,OutputStream,Options)`/`fileName`/`MIME_ZIP` :31/:24/:13, `OperationLog.readLastSession/snapshot/tailSnapshot/clear` :99/:93/:102/:104, `DextopLog.log(level,tag,msg,fields,stack,ctx)` :62, SAF launcher `exportZipLauncher` `MainActivity.kt:146-163`.
- `linux/LinuxChannel.kt:28-631` — `app.freedextop/linux` handler; **91 dispatch arms / 95 distinct method-name literals** (brief says ~90; enumerate all 95 below so none is lost). Transport helpers `remote()` :29-35, `jsonMap/jsonList/toMap/toValue` :649-663; service events `onProgress/onLog/onContainersChanged/onConnectionChanged` :639-647.
- Native targets: `LinuxContainerClient.call(context, action, onSuccess, onError)` + `syncExperimentalFastMode/privilegeAvailable/connect/switchExecution/service/addListener` (LinuxContainerClient.kt), AIDL `ILinuxContainerService.aidl:12-186` (transactions 1-90), domain singletons `LinuxFolders/LinuxUsb/LinuxStoreJobs/LinuxSnapshots/LinuxCalendarBridge/LinuxAndroidWindows/LinuxShortcutInstaller/...`.
- Patterns followed: `ResolutionRepository.kt:17-89`, `LaptopKeyboardThemeRepository.kt:25`. No builds, no source edits.

## Findings
- **P0 — No typed repository exists; all 95 Linux + 11 diagnostics RPCs live inline in two `when` blocks** (`LinuxChannel.kt:36-629`, `MainActivity.kt:358-848`). M3 removes Flutter; if the surface is transcribed by hand, parity is unverifiable and RPCs will be dropped. Fix: the two skeletons + codegen + machine-checked parity list below; the generated repository must delegate to the existing native collaborators, never reimplement them.
- **P1 — Flutter messenger is main-thread-only** (`MainActivity.kt:229`), yet `remote()` (`LinuxChannel.kt:29-35`) calls `result.success` on the Binder callback thread, while other arms hop via raw `Thread{}` + `Handler.post` (:86-94,:96-104,:266-274,:486-489). Fix: repository owns a single `CoroutineScope(SupervisorJob()+Dispatchers.IO)`; all native calls run on IO, all state emits on `Dispatchers.Main.immediate`, scope cancelled on `onDestroy`/`cleanUpFlutterEngine`.
- **P1 — 6/11 diagnostics RPCs need Activity/SAF, not a pure repository** (`sendDeviceReportEmail`, `shareDiagnosticReport`, `exportDiagnosticZip`+`exportZipLauncher` `MainActivity.kt:146-163`, `shareDiagnosticZip`, `deviceReportIdentity` uses `packageManager`). Fix: an output port (`DiagnosticsOutput`) implemented by the host Activity/`Compose` navigator; repository emits intents/requests, host performs them.
- **P2 — Untyped payloads** (`Map<String,Any?>` from `toMap` `LinuxChannel.kt:655`, `DeviceDiagnostics.report()` `DeviceDiagnostics.kt:114-152`): key drift between Dart and Kotlin is silent. Fix: typed request/response data classes per domain; keep a `raw: Map<String,Any?>` escape hatch only for pass-through JSON (`settings`, `setSettings`, `integrationAction`).
- **P2 — Service events currently feed only Flutter** (`LinuxChannel.kt:639-647`); after Flutter removal there is no consumer. Fix: `LinuxRuntimeRepository` implements `LinuxContainerClient.Listener` and writes the same events into `StateFlow`, so ViewModels observe one source of truth.
- **P3 — name collisions across the two channels** (`status`, `apps`, `launchApp` exist in both). Generated symbols must be namespaced (`Diagnostics*` / `Linux*`) with the original `method` literal retained in the registry.

## Deliverable — exact skeletons (design only)

### `DiagnosticsRepository.kt`
```kotlin
package moe.n4tsu.dextop.repository

import kotlinx.coroutines.flow.StateFlow

internal data class Metrics(
    val memoryMb: Long, val availableMemoryMb: Long, val batteryPercent: Int,
    val powerWatts: String, val cpuTemperature: String, val inputMode: String,
    val refreshRate: Double, val fps: Double,
)
internal data class DiagnosticsState(
    val capabilities: Map<String, Any> = emptyMap(),
    val metrics: Metrics? = null,
    val lastSessionLog: String = "",
    val report: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)
/** UI side-effects the repository may request but must never perform itself. */
internal interface DiagnosticsOutput {
    fun sendEmail(recipient: String, subject: String, body: String)
    fun shareText(subject: String, body: String)
    fun pickZipDestination(options: DiagnosticExport.Options, onResult: (Uri?) -> Unit)
    fun shareZip(options: DiagnosticExport.Options)
    fun identity(): Map<String, Any?>          // DeviceReportIdentity map from MainActivity.kt:668-687
}
internal interface DiagnosticsRepository {
    val state: StateFlow<DiagnosticsState>
    suspend fun refresh()                                   // -> DeviceDiagnostics.report()
    suspend fun refreshMetrics(inputMode: String, fps: Double)
    suspend fun buildReport(options: DiagnosticExport.Options = DiagnosticExport.Options()): String
    suspend fun lastSessionLog(): String
    fun clearLog()
    fun log(level: String, tag: String, message: String, fields: Map<String, Any?>, stack: String? = null)
    fun deviceReportIdentity(): Map<String, Any?>           // pure data; no Intent here
    fun sendDeviceReportEmail(subject: String, body: String)
    fun shareDiagnosticReport()
    fun exportDiagnosticZip(options: DiagnosticExport.Options)
    fun shareDiagnosticZip(options: DiagnosticExport.Options)
}

internal class DiagnosticsRepositoryImpl(
    private val diagnostics: DeviceDiagnostics,
    private val output: DiagnosticsOutput,
    private val io: CoroutineScope,                          // SupervisorJob()+Dispatchers.IO
) : DiagnosticsRepository {
    private val _state = MutableStateFlow(DiagnosticsState())
    override val state: StateFlow<DiagnosticsState> = _state.asStateFlow()
    // 1:1 delegation, no logic:
    // refresh()          -> _state.update { diagnostics.report() }
    // refreshMetrics()   -> DeviceDiagnostics.metrics(inputMode, fps) -> map to Metrics
    // buildReport()      -> DiagnosticReport(context).build(options)
    // lastSessionLog()   -> OperationLog.readLastSession(context)
    // clearLog()         -> OperationLog.clear(context)
    // log()              -> DextopLog.log(level, tag, message, fields, stack, context)
    // email/share/zip    -> output.* (SAF launcher stays in the Activity host)
}
```
`DiagnosticsState` fields are exactly the 5 data-producing RPCs; the 6 UI RPCs are methods that delegate through `DiagnosticsOutput`.

### `LinuxRuntimeRepository.kt` — typed façade + generated domain models
```kotlin
package moe.n4tsu.dextop.repository

// ---- one data class per domain (generated; fields = call.argument<*> keys) ----
internal data class ContainerArgs(val id: String, val force: Boolean = false)
internal data class InstallArgs(val name: String, val distro: String = "debian", val options: Map<String, Any?> = emptyMap())
internal data class StoreArgs(val id: String, val source: String? = null, val action: String? = null,
                              val packages: List<String> = emptyList(), val offset: Int = 0, val query: String = "")
internal data class ShareArgs(val id: String, val kind: String, val enabled: Boolean)
internal data class ScanArgs(val id: String, val category: String, val path: String, val offset: Int = 0)
internal data class SnapArgs(val id: String, val file: String, val uri: String)
internal data class WinArgs(val id: String, val action: String, val packageName: String, val spec: String? = null)
internal data class MacosArgs(val id: String, val operation: String, val value: String = "")
internal data class IntegrationArgs(val id: String, val request: Map<String, Any?>)
internal data class AppArgs(val id: String, val desktop: String, val label: String? = null, val icon: String? = null)

internal data class LinuxState(
    val supportedDevice: Boolean = false, val privilege: Boolean = false,
    val connected: Boolean = false, val executionMode: String = "", val plasmaV2: Boolean = false,
    val containers: List<Map<String, Any?>> = emptyList(),
    val progress: Map<String, Int> = emptyMap(), val logs: Map<String, List<String>> = emptyMap(),
    val storeJobs: List<Map<String, Any?>> = emptyList(), val error: String? = null,
)
internal interface LinuxRuntimeRepository {
    val state: StateFlow<LinuxState>
    suspend fun <T> rpc(action: (ILinuxContainerService) -> T): T   // bridges LinuxContainerClient.call
    // + 95 generated suspend fun(...) mirroring LinuxChannel method names
}
internal class LinuxRuntimeRepositoryImpl(
    private val context: Context, private val io: CoroutineScope,
) : LinuxRuntimeRepository, LinuxContainerClient.Listener {
    private val _state = MutableStateFlow(LinuxState())
    override val state = _state.asStateFlow()
    // suspendCancellableCoroutine { cont -> LinuxContainerClient.call(context, action,
    //     { resume(it) }, { resumeWithException(it) }) }; dispatch state updates on Main
    // Listener callbacks (LinuxChannel.kt:639-647) -> _state.update { ... } (no channel.invokeMethod)
    init { LinuxContainerClient.addListener(this) }
    fun dispose() = LinuxContainerClient.removeListener(this)
}
```
Every generated method body is a one-line delegation to the existing implementation, e.g.:
```kotlin
override suspend fun list(args: Unit) = rpc { it.listContainers() }            // jsonList
override suspend fun setUsbPolicy(policy: String) = withContext(Dispatchers.IO) { LinuxUsb.setPolicy(context, policy) }
override suspend fun snapshots() = rpc { /* existing snapshots() lambda, LinuxChannel.kt:254-261 */ }
```
No business logic is copied; the exact lambdas at `LinuxChannel.kt:36-629` move behind typed signatures.

### Generation strategy for 95 methods
1. **Single source of truth** — checked-in spec `linux_rpc_spec.json` (or a Kotlin DSL in `buildSrc`): each entry `{method, domain, args:[{name,type}], returns, transport: AIDL|NATIVE_SINGLETON|ACTIVITY, thread}`.
2. **Generator** (Gradle task or KSP) emits three files from that spec: (a) domain request/response `data class`es, (b) `LinuxRuntimeRepository` interface + `Impl` overrides whose bodies call `rpc{}`/the named domain singleton, (c) a `LinuxRpcRegistry` map `method -> (args)->Any?` used by any remaining legacy channel bridge.
3. **Reducer for grouped arms**: one spec row per *method literal* (not per `when` arm), so the 2-name arms (`setUsbPolicy`/`setUsbForLinux` :96, `storeSourcePrepare`/`storeSourceRun` :144) and the 3-name macOS arm (:535) generate distinct methods sharing one implementation helper.
4. **State**: generator registers each listener-driven method (`sessions`, `storeJobs`, `resources`, `list`, `listAll`, `settings`) to write its typed slice of `LinuxState`; `onProgress/onLog/onContainersChanged/onConnectionChanged` map to `progress/logs/containers/connected`.
5. **Parity gate (CI, no device)**: generator fails the build if `setOf(spec.method) != goldenMethodSet`, where `goldenMethodSet` is a checked-in literal produced by parsing the `when (call.method)` string literals in `LinuxChannel.kt`. Any future RPC added to the channel without a spec row breaks the build. Same gate for the 11 diagnostics names parsed from `MainActivity.kt`.

### Parity checklist — Diagnostics (11/11)
`diagnostics` · `lastSessionLog` · `deviceReportIdentity` · `sendDeviceReportEmail` · `metrics` · `diagnosticReport` · `clearDiagnosticLog` · `shareDiagnosticReport` · `logEvent` · `exportDiagnosticZip` · `shareDiagnosticZip`

### Parity checklist — Linux (95 literals / 91 arms)
- Session/capability (7): `syncExperimentalFastMode`, `status`, `offlineOverview`, `executionMode`, `setExecutionMode`, `connect`, `resources`
- Containers lifecycle (11): `catalog`, `list`, `listAll`, `install`, `setup`, `cancel`, `remove`, `repair`, `resetLinux`, `setKeyring`, `storeState`(see store)
- Compat/sharing/folders (9): `compatStatus`, `setContainerGrant`, `addFolder`, `removeFolder`, `setUsbPolicy`, `setUsbForLinux`, `openStorageAccess`, `liftPhantomLimit`, `requestPermission`
- Desktop/sessions/windows (6): `openDesktop`, `sessions`, `sessionCapture`, `focusSession`, `closeApp`, `pickDocument`
- Container ops (9): `runtimeLogs`, `installLog`, `resetSession`, `run`, `containerUsage`, `storageBreakdown`, `cleanContainer`, `stopProcess`, `appPackage`
- Snapshots/backups (7): `snapshots`, `createSnapshot`, `restoreSnapshot`, `deleteSnapshot`, `exportSnapshot`, `importBackup`, `containerUsage`(shared)
- Apps/shortcuts (11): `apps`, `launchApp`, `installAppImage`, `removeAppImage`, `uninstallApp`, `createShortcut`, `removeShortcut`, `shortcuts`, `launcherInstall`, `setLauncherInstall`, `openTerminal`
- Settings/theme/clipboard (7): `settings`, `setSettings`, `linuxTheme`, `setLinuxTheme`, `keyboardButtonVisible`, `setKeyboardButtonVisible`, `clipboardSync`, `setClipboardSync`
- Store (17): `storeSources`, `storeSourcePrepareJobs`, `setStoreSource`, `storeSourcePrepare`, `storeSourceRun`, `storeSourceCatalog`, `storeSourceReady`, `storeSourceStatus`, `storeState`, `storePackages`, `storeJobMetadata`, `storeRun`, `storeJobs`, `storeCancel`, `aptSearch`, `aptDiagnose`, `catalog`(shared)
- Integrations/calendar/print (5): `integrationStatus`, `integrationAction`, `calendarList`, `calendarSync`, `printJob`
- macOS (4): `macosStatus`, `macosRegister`, `macosUnregister`, `macosInstall`
- Windows apps (4): `winSearch`, `winDetails`, `winInstalled`, `winRun`
Total distinct literals = 95 (terminal matching; shared rows counted once in their primary domain).

## Risks / device testing needed
- Reconcile the 95-vs-90 count with whoever authored the brief before merge; the generator gate uses the parsed literal set, so the design is safe either way.
- Verify on Redmi Pad Pro 5G / HyperOS 3: SAF `ACTION_CREATE_DOCUMENT` + `FileProvider` `${packageName}.fileprovider` still resolve (HyperOS DocumentsUI ignores `EXTRA_INITIAL_URI` — kept out at `MainActivity.kt:793`); ZIP export caps hold (2 MB total, `DiagnosticExport.kt:14-15`).
- Confirm binder callbacks are never used to mutate StateFlow off `Dispatchers.Main`; test with Shizuku binder death mid-call (`binderDeadListener` `MainActivity.kt:242-247`) — every RPC must surface `LinuxState.error`, not crash.
- Generated `rpc{}` must preserve the privileged-call off-main-thread behavior of `setUsbPolicy`/`setUsbForLinux` (:96-104) and `uninstallApp` (:266-274); moving them to `Dispatchers.IO` is required, not optional.
- Sensitive sections of diagnostic exports stay opt-in (`DiagnosticExport.Options` defaults all false, :18-22); the repository must not add convenience overloads that enable them implicitly.
