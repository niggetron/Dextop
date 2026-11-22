# W3A (M1/M2) — Wave 3 Probe Engine Report

## Scope inspected
- `CapabilityProbe.kt` (45 LOC): flat 6-probe `Map<String, CapabilityProbeResult>`; `runCatching` wrapper; correct "version ≠ support" stance at :13, but no structured tree, no missing-reason string, re-runs reflection on every call.
- `DesktopEnvironment.kt` (269 LOC): `DeviceIdentity`, `DeviceMatch`, `DesktopEnvironment` (workaround flags, strategy lists), `DesktopEnvironmentRegistry.resolve()`; rules in `DeviceProfiles.kt` (14 rules, narrow Redmi `duchamp` sdk36 rule at :69-84).
- `DisplayMirrorBackend.kt` (835 LOC): `VirtualDisplayPlatform.inspect()` (:717-745) is pure reflection — safe probe source; `open*` flag ladders (trusted/untrusted, public/private, refresh rate) must NOT be invoked by a probe; `MirrorAttachBackend.isSupported()` is the runtime authority.
- `InputDispatcher.kt`: `IInputManager.injectInputEvent` + `InputEvent.setDisplayId` reflection; `input/PrivilegedInputClient.kt`: binder/engine lifecycle (bind/start/inject) — side-effecting, expose only state (`isConnected`, `probe()`) to the snapshot.
- `NativeStrings.kt` (generated, ARB-backed): reason strings must be new ARB keys, not literals.
- Surfaces today: `DiagnosticReport.kt:85-87` builds a *fresh* `CapabilityProbe`; `DiagnosticExport.kt:31-59` has no capability entry; `MirrorService.kt:12124/12167/12226` repeat the full probe set on each display create.

## Findings
- **P1** `CapabilityProbe.kt:8-45` — flat, uncached, reason-less results; cannot drive deterministic backend selection. Fix: replace with typed `CapabilitySnapshot` + cached engine (below).
- **P1** `DiagnosticReport.kt:85` / `MirrorService.kt:12124,12167,12226` — four independent probe runs per session; report may disagree with the session's actual capability view. Fix: single `CapabilitySnapshotStore` for all consumers.
- **P2** `DeviceDiagnostics.kt:20-153` — parallel flat capability map (mouse/keyboard/display/privilege) duplicates probe logic. Fix: `CompatibilityProfile.privilege` wraps `DeviceDiagnostics.report()`; keep it the only place reading those prefs.
- **P2** `DiagnosticExport.kt:31-59` — exported ZIP lacks a capability snapshot. Fix: add always-on `capability-snapshot.txt` (read-only data, safe by default).
- **P2** `CapabilityProbe.kt:42-44` — `detail` conflates success label and failure; no user-facing "why". Fix: `Probe(supported, reason, detail)` with `reason` from ARB.
- **P3** `NativeStrings.kt:1-5` is generated — add `nativeProbe*Unavailable` keys to `lib/l10n/app_*.arb` and regenerate; do not hand-edit.

## Deliverable — exact files / code sketch

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/CapabilitySnapshot.kt`
```kotlin
package moe.n4tsu.dextop

/** One read-only fact. `reason` is a user-facing missing-reason string; never thrown. */
internal data class Probe(
    val supported: Boolean,
    val reason: String? = null,     // non-null iff !supported
    val detail: String? = null,
) {
    val ok: Boolean get() = supported
    companion object {
        fun yes(detail: String? = null) = Probe(true, null, detail)
        fun no(reason: String, detail: String? = null) = Probe(false, reason, detail)
    }
}

internal enum class BackendId { A, B, C, D, E, F }   // blueprint backends A–F

internal data class CapabilitySnapshot(
    val identity: DeviceIdentity,
    val platform: PlatformCapabilities,
    val display: DisplayCapabilities,
    val windowing: WindowingCapabilities,
    val input: InputCapabilities,
    val graphics: GraphicsCapabilities,
    val privilege: PrivilegeCapabilities,
    val compatibility: CompatibilityProfile,
    val capturedAtMillis: Long,
) {
    /** Flat, sanitized view consumed by DiagnosticReport + DiagnosticExport. */
    fun toReportLines(): List<String> = buildList {
        add("[CAPABILITY SNAPSHOT]")
        add("capturedAt: $capturedAtMillis")
        section("IDENTITY", listOf(
            "manufacturer" to identity.manufacturer, "brand" to identity.brand,
            "model" to identity.model, "device" to identity.device,
            "product" to identity.product, "fingerprint" to identity.fingerprint,
            "sdk" to identity.sdk))
        section("PLATFORM", platform.asFields())
        section("DISPLAY", display.asFields())
        section("WINDOWING", windowing.asFields())
        section("INPUT", input.asFields())
        section("GRAPHICS", graphics.asFields())
        section("PRIVILEGE", privilege.asFields())
        section("COMPATIBILITY", listOf(
            "environmentId" to compatibility.environmentId,
            "environmentName" to compatibility.environmentName,
            "matchedRuleId" to compatibility.matchedRuleId,
            "recommendedBackend" to compatibility.recommendedBackend.name,
            "displayCreationStrategies" to compatibility.displayCreationStrategies,
            "mirrorStrategies" to compatibility.mirrorStrategies,
            "windowingStrategies" to compatibility.windowingStrategies,
            "activeWorkarounds" to compatibility.activeWorkarounds))
    }

    private fun MutableList<String>.section(name: String, fields: List<Pair<String, Any?>>) {
        add("[$name]"); fields.forEach { (k, v) -> add("$k: ${describe(v)}") }
    }
    private fun describe(v: Any?): String = when (v) {
        is Probe -> if (v.ok) "supported" else "unsupported reason=${v.reason} detail=${v.detail}"
        is Collection<*> -> v.joinToString()
        else -> v?.toString() ?: "unknown"
    }
    // *.asFields() are simple listOf("key" to value) builders on each data class above.
}

internal data class PlatformCapabilities(
    val sdk: Int, val release: String, val incremental: String,
    val securityPatch: String, val previewSdk: Int, val abis: List<String>,
    val android16OrNewer: Boolean,
    val desktopModeApi: Probe, val secondaryDisplayApi: Probe, val pointerCaptureApi: Probe,
) { fun asFields() = listOf("sdk" to sdk, "release" to release, "incremental" to incremental,
    "securityPatch" to securityPatch, "previewSdk" to previewSdk, "abis" to abis,
    "android16OrNewer" to android16OrNewer, "desktopModeApi" to desktopModeApi,
    "secondaryDisplayApi" to secondaryDisplayApi, "pointerCaptureApi" to pointerCaptureApi) }

internal data class DisplayCapabilities(
    val overlayDisplaySetting: Probe, val displayManager: Probe,
    val physicalDisplayCount: Int, val maxRefreshRateHz: Float?,
    val virtualDisplayConfig: Probe, val trustedVirtualDisplay: Probe,
    val untrustedVirtualDisplay: Probe, val setRequestedRefreshRate: Probe,
    val resizeVirtualDisplay: Probe, val mirrorSurfaceControl: Probe,
    val mirrorWindowManager: Probe, val surfaceControlTransforms: Probe,
    val accessibilityOverlayToken: Probe,
) { fun asFields() = /* same pattern: one line per property */ TODO() }

internal data class WindowingCapabilities(
    val freeform: Probe, val forceResizableGlobal: Probe, val launchOnSecondary: Probe,
    val cmdWindow: Probe, val activityTaskManager: Probe, val sessionDecorations: Probe,
) { fun asFields() = /* same pattern */ TODO() }

internal data class InputCapabilities(
    val injectInputEvent: Probe, val setDisplayId: Probe, val privilegedInputBinder: Probe,
    val multiTouch: Probe, val stylus: Probe, val externalMouse: Probe,
    val externalKeyboard: Probe, val gamepad: Probe, val pointerCapture: Probe,
    val uinputNodePresent: Probe,
) { fun asFields() = /* same pattern */ TODO() }

internal data class GraphicsCapabilities(
    val glesVersions: List<String>, val vulkan: Probe, val hardwareBuffer: Probe,
    val ahbZeroCopy: Probe, val asurfaceControl: Probe, val aChoreographer: Probe,
    val adpfPerformanceHint: Probe, val aaAudio: Probe,
    val turnipPackaged: Probe, val virglPackaged: Probe,
) { fun asFields() = /* same pattern */ TODO() }

internal data class PrivilegeCapabilities(
    val provider: String, val binderAlive: Probe, val writeSecureSettings: Probe,
    val accessibilityService: Probe, val systemAlertWindow: Probe, val shellExecute: Probe,
) { fun asFields() = /* same pattern */ TODO() }

internal data class CompatibilityProfile(
    val environmentId: String, val environmentName: String, val matchedRuleId: String?,
    val recommendedBackend: BackendId,
    val displayCreationStrategies: List<String>, val mirrorStrategies: List<String>,
    val windowingStrategies: List<String>, val activeWorkarounds: Set<String>,
)
```
(`TODO()` placeholders above expand to one `"property" to property` pair each; no logic.)

### NEW `android/app/src/main/kotlin/moe/n4tsu/dextop/CapabilitySnapshotEngine.kt`
```kotlin
package moe.n4tsu.dextop

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import java.io.File

/** Injectable, side-effect-free seams so probes are unit-testable on a JVM. */
internal interface CapabilitySources {
    fun hasFeature(name: String): Boolean
    fun classExists(name: String): Boolean
    fun methodExists(cls: String, method: String, vararg params: Class<*>): Boolean
    fun setting(key: String): String?                 // getString only; never putString
    fun exists(path: String): Boolean                 // File.exists
    fun displayCount(): Int
    fun maxRefreshRateHz(): Float?
    fun inputDevices(): List<DeviceInfo>
    fun diagnostics(): Map<String, Any>
    fun nativeLibs(): List<String>
    data class DeviceInfo(val sources: Int, val keyboardType: Int)
}

private class AndroidCapabilitySources(private val context: Context) : CapabilitySources {
    private val pm get() = context.packageManager
    private val displayManager get() = context.getSystemService(DisplayManager::class.java)
    override fun hasFeature(n: String) = pm.hasSystemFeature(n)
    override fun classExists(n: String) = runCatching { Class.forName(n); true }.getOrDefault(false)
    override fun methodExists(c: String, m: String, vararg p: Class<*>) = runCatching {
        Class.forName(c).getMethod(m, *p); true
    }.getOrDefault(false)
    override fun setting(k: String) = runCatching { Settings.Global.getString(context.contentResolver, k) }.getOrNull()
    override fun exists(p: String) = File(p).exists()
    override fun displayCount() = displayManager.displays.size
    override fun maxRefreshRateHz() = displayManager.displays.maxOfOrNull { d ->
        d.supportedModes.maxOfOrNull { it.refreshRate } ?: d.refreshRate
    }
    override fun inputDevices() = InputDevice.getDeviceIds().mapNotNull { id ->
        InputDevice.getDevice(id)?.let { DeviceInfo(it.sources, it.keyboardType) }
    }
    override fun diagnostics() = DeviceDiagnostics(context).report()
    override fun nativeLibs() = runCatching {
        File(context.applicationInfo.nativeLibraryDir).list()?.toList().orEmpty()
    }.getOrDefault(emptyList())
}

/**
 * Read-only capability engine. Probes use only reflection metadata, PackageManager,
 * DisplayManager.displays, InputDevice, Settings.getString and File.exists — no writes,
 * no binds, no display creation, no process spawn. Cached once per session.
 */
internal class CapabilitySnapshotEngine(
    sources: CapabilitySources,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(AndroidCapabilitySources(context))

    @Volatile private var cached: CapabilitySnapshot? = null
    fun snapshot(): CapabilitySnapshot = cached ?: synchronized(this) {
        cached ?: build().also { cached = it }
    }
    fun invalidate() { synchronized(this) { cached = null } }   // session teardown / tests only

    private fun build(): CapabilitySnapshot {
        val id = DeviceIdentity.current()
        val env = DesktopEnvironmentRegistry.resolve(id)
        val rule = DeviceProfiles.rules.firstOrNull { it.match.matches(id) }?.id
        val platform = probePlatform(id)
        val display = probeDisplay()
        val windowing = probeWindowing()
        val graphics = probeGraphics()
        return CapabilitySnapshot(
            identity = id, platform = platform, display = display,
            windowing = windowing, input = probeInput(), graphics = graphics,
            privilege = probePrivilege(),
            compatibility = CompatibilityProfile(
                env.id, env.displayName, rule, selectBackend(id, platform, display, windowing, graphics, rule),
                env.displayCreationStrategies, env.mirrorStrategies, env.windowingStrategies,
                buildSet {
                    if (env.untrustedMirrorDisplay) add("untrustedMirrorDisplay")
                    if (env.fullscreenApplicationDisplays) add("fullscreenApplicationDisplays")
                    if (env.passivePlasmaShellFocus) add("passivePlasmaShellFocus")
                    if (env.maxRateApplicationDisplays) add("maxRateApplicationDisplays")
                    if (env.plasmaApplicationOverlay) add("plasmaApplicationOverlay")
                    if (env.rebuildMirrorAfterLiveResize) add("rebuildMirrorAfterLiveResize")
                    if (env.privateApplicationDisplays) add("privateApplicationDisplays")
                    if (env.pinPhysicalMouseToDesktop) add("pinPhysicalMouseToDesktop")
                    if (env.directDeckKeyboardToApp) add("directDeckKeyboardToApp")
                    if (env.directPlasmaDisplay) add("directPlasmaDisplay")
                }),
            now())
    }

    // Generic probes: reason is an ARB key resolved through NativeStrings; nothing throws.
    private inline fun probe(reasonKey: String, block: () -> Any?): Probe = runCatching {
        block(); Probe.yes()
    }.getOrElse { Probe.no(NativeStrings.text(reasonKey), "${it.javaClass.simpleName}: ${it.message}") }

    private fun probePlatform(id: DeviceIdentity) = PlatformCapabilities(
        id.sdk, Build.VERSION.RELEASE, Build.VERSION.INCREMENTAL,
        Build.VERSION.SECURITY_PATCH, Build.VERSION.PREVIEW_SDK_INT, Build.SUPPORTED_ABIS.toList(),
        id.sdk >= 36,
        probe("nativeProbeDesktopModeUnavailable") { s.hasFeature(PackageManager.FEATURE_DESKTOP_MODE) },
        probe("nativeProbeSecondaryDisplayUnavailable") { s.methodExists("android.app.ActivityOptions", "setLaunchDisplayId", Int::class.javaPrimitiveType!!) },
        probe("nativeProbePointerCaptureUnavailable") { s.methodExists("android.view.View", "requestPointerCapture") },
    )

    private fun probeDisplay() = DisplayCapabilities(
        probe("nativeProbeOverlaySettingUnavailable") { s.setting(DisplayMirrorBackend.DISPLAY_SPECIFICATION) },
        probe("nativeProbeDisplayManagerUnavailable") { s.displayCount() > 0 },
        s.displayCount(), s.maxRefreshRateHz(),
        probe("nativeProbeVirtualDisplayUnavailable") { VirtualDisplayPlatform.inspect() },
        probe("nativeProbeVirtualDisplayUnavailable") { s.methodExists("android.hardware.display.IDisplayManager", "createVirtualDisplay") },
        probe("nativeProbeUntrustedDisplayUnavailable") { s.methodExists("android.hardware.display.VirtualDisplayConfig\$Builder", "setFlags", Int::class.javaPrimitiveType!!) },
        probe("nativeProbeRefreshRateUnavailable") { s.methodExists("android.hardware.display.VirtualDisplayConfig\$Builder", "setRequestedRefreshRate", Float::class.javaPrimitiveType!!) },
        probe("nativeProbeResizeUnavailable") { s.methodExists("android.hardware.display.IDisplayManager", "resizeVirtualDisplay") },
        probe("nativeProbeSurfaceControlMirrorUnavailable") { s.methodExists("android.view.SurfaceControl", "mirrorDisplay", Int::class.javaPrimitiveType!!) },
        probe("nativeProbeWindowManagerMirrorUnavailable") { s.classExists("android.view.IWindowManager") },
        probe("nativeProbeSurfaceControlTransformsUnavailable") { s.methodExists("android.view.SurfaceControl\$Transaction", "setWindowCrop", android.view.SurfaceControl::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) },
        probe("nativeProbeOverlayTokenUnavailable") { Build.VERSION.SDK_INT >= 36 },   // version gate only; device-verify
    )

    private fun probeWindowing() = WindowingCapabilities(
        probe("nativeProbeFreeformUnavailable") { s.hasFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) || s.setting("enable_freeform_support") == "1" },
        probe("nativeProbeFreeformUnavailable") { s.setting("force_resizable_activities") },
        probe("nativeProbeLaunchOnSecondaryUnavailable") { s.methodExists("android.app.ActivityTaskManager", "startActivity") },
        probe("nativeProbeCmdWindowUnavailable") { s.exists("/system/bin/cmd") && s.diagnostics()["privilegeProvider"] != null },
        probe("nativeProbeAtmUnavailable") { s.classExists("android.app.ActivityTaskManager\$Stub") },
        probe("nativeProbeDecorationsUnavailable") { s.setting("force_desktop_mode_on_external_displays") },
    )

    private fun probeInput() = InputCapabilities(
        probe("nativeProbeInputInjectUnavailable") { s.methodExists("android.hardware.input.IInputManager", "injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType!!) },
        probe("nativeProbeInputDisplayUnavailable") { s.methodExists("android.view.InputEvent", "setDisplayId", Int::class.javaPrimitiveType!!) },
        probe("nativeProbeInputBinderUnavailable") { s.diagnostics()["privilegeProvider"] != "none" },
        probe("nativeProbeMultiTouchUnavailable") { s.hasFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH) },
        probe("nativeProbeStylusUnavailable") { s.hasFeature(PackageManager.FEATURE_TOUCHSCREEN_STYLUS) },
        probe("nativeProbeMouseUnavailable") { s.inputDevices().any { it.sources and InputDevice.SOURCE_MOUSE != 0 } },
        probe("nativeProbeKeyboardUnavailable") { s.inputDevices().any { it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC } },
        probe("nativeProbeGamepadUnavailable") { s.hasFeature(PackageManager.FEATURE_GAMEPAD) },
        probe("nativeProbePointerCaptureUnavailable") { s.methodExists("android.view.View", "requestPointerCapture") },
        probe("nativeProbeUinputUnavailable") { s.exists("/dev/uinput") },
    )

    private fun probeGraphics() = GraphicsCapabilities(
        buildList {
            s.diagnostics().keys.filter { it.startsWith("gles=") }.forEach { add(it.removePrefix("gles=")) }
        },
        probe("nativeProbeVulkanUnavailable") { s.hasFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) },
        probe("nativeProbeHardwareBufferUnavailable") { s.classExists("android.graphics.HardwareBuffer") },
        probe("nativeProbeAhbUnavailable") { s.classExists("android.graphics.HardwareBuffer") && s.nativeLibs().any { it.contains("ahb", true) } },
        probe("nativeProbeASurfaceControlUnavailable") { s.classExists("android.view.SurfaceControl\$ASurfaceControl") },
        probe("nativeProbeAChoreographerUnavailable") { s.methodExists("android.view.Choreographer", "postFrameCallback") },
        probe("nativeProbeAdpfUnavailable") { s.classExists("android.os.PerformanceHintManager") },
        probe("nativeProbeAaAudioUnavailable") { s.nativeLibs().any { it.contains("aaudio", true) } || s.exists("/system/lib64/libaaudio.so") },
        probe("nativeProbeTurnipUnavailable") { s.nativeLibs().any { it.contains("turnip", true) || it.contains("vulkan", true) } },
        probe("nativeProbeVirglUnavailable") { s.nativeLibs().any { it.contains("virgl", true) } },
    )

    private fun probePrivilege() = PrivilegeCapabilities(
        s.diagnostics()["privilegeProvider"]?.toString() ?: "none",
        probe("nativeProbePrivilegeUnavailable") { PrivilegedAccess("DextopProbe").isAvailable() },
        probe("nativeProbeSecureSettingsUnavailable") { s.diagnostics()["secureSettings"] == true },
        probe("nativeProbeAccessibilityUnavailable") { s.diagnostics()["accessibility"] == true },
        probe("nativeProbeOverlayUnavailable") { s.diagnostics()["overlayWritable"] == true },
        probe("nativeProbeShellUnavailable") { s.exists("/system/bin/sh") && s.diagnostics()["privilegeProvider"] != "none" },
    )

    private fun selectBackend(id: DeviceIdentity, p: PlatformCapabilities, d: DisplayCapabilities,
                              w: WindowingCapabilities, g: GraphicsCapabilities, rule: String?) = when {
        rule == "xiaomi_redmi_pad_pro_5g" || rule == "xiaomi_hyperos" -> BackendId.E
        id.manufacturer.equals("samsung", true) -> BackendId.B
        p.android16OrNewer && d.trustedVirtualDisplay.ok && w.launchOnSecondary.ok -> BackendId.A
        d.virtualDisplayConfig.ok -> BackendId.C
        g.turnipPackaged.ok || g.virglPackaged.ok -> BackendId.D
        else -> BackendId.F
    }
}

/** One snapshot per process; reset on session teardown or in tests. */
internal object CapabilitySnapshotStore {
    @Volatile private var engine: CapabilitySnapshotEngine? = null
    fun get(context: Context): CapabilitySnapshot = (engine ?: synchronized(this) {
        engine ?: CapabilitySnapshotEngine(context.applicationContext).also { engine = it }
    }).snapshot()
    fun reset() { synchronized(this) { engine?.invalidate(); engine = null } }
}
```

### Integration edits (no source changes made by this agent — sketches only)
- `DiagnosticReport.kt`: after `:24` add `val snapshot = CapabilitySnapshotStore.get(context)`; replace `:85-87` probe loop with `snapshot.toReportLines().forEach { sb.appendLine(it) }`; keep `diagnostics` map for `[DEXTOP COMPATIBILITY]` fields.
- `DiagnosticExport.kt`: inside `write()` add, before `session-log.txt`:
  `add("capability-snapshot.txt", CapabilitySnapshotStore.get(context).toReportLines().joinToString("\n"))` — no `Options` gate (read-only); existing `add()` sanitizes and caps size.
- `MirrorService.kt`: replace the three `CapabilityProbe(...).run()` blocks (`:12124`, `:12167`, `:12226`) with `CapabilitySnapshotStore.get(this).toReportLines().filter { it.startsWith("probe")... }` or a single `[CAPABILITY]` log line; call `CapabilitySnapshotStore.reset()` in teardown.
- `CapabilityProbe.kt`: retain `CapabilityProbeResult` as a deprecated alias and make `run()` delegate to `CapabilitySnapshotStore.get(context).toLegacyMap()` for any remaining callers; delete once call sites above migrate.
- `lib/l10n/app_*.arb`: add `nativeProbe*Unavailable` reason keys listed above, then regenerate `NativeStrings.kt`.

### Unit-test strategy (`CapabilitySnapshotEngineTest`, local JVM + FakeCapabilitySources)
1. **Never throws / reasons present** — fake all seams to throw; assert snapshot builds, every `Probe.supported=false` and `reason` non-blank.
2. **Tree completeness** — `toReportLines()` contains all 8 section headers and one line per property.
3. **Rule isolation** — table of `DeviceIdentity` → `matchedRuleId`/`recommendedBackend`: RedmiPadPro5G (`duchamp`, xiaomi, sdk36) → E + narrow rule; generic xiaomi → E + `xiaomi_hyperos`; Samsung `q7mq` sdk36 → B; Google sdk36 → A; unknown sdk33 → F; assert a Xiaomi *phone* (non-`duchamp`) never matches `xiaomi_redmi_pad_pro_5g`.
4. **Cache once** — counting fake; 5× `snapshot()` → one full probe pass, reference-equal results; `invalidate()` → new instance and counter increments.
5. **No side effects** — fake records calls; assert zero `putString`/`bind`/`openDisplay`/`exec` and that `Class.forName` is metadata-only.
6. **Serialization** — all lines pass `OperationLog.sanitizeDocument`, total under `DiagnosticExport.MAX_ENTRY_BYTES`, no token/SECRET strings.

## Risks / device testing needed
- Reflection success is **not** capability proof (existing comment `CapabilityProbe.kt:13`); `cmd window`, accessibility overlay token and trusted/untrusted display are version/static gates — runtime `MirrorAttachBackend.isSupported()`/attach remains authoritative. Do not open displays or spawn `cmd` inside a probe.
- `LinuxGpuProbe` runs workloads → **excluded** from the read-only snapshot; Turnip/VirGL entries are package/file checks only. Confirm actual GPU path on-device and reconcile with M1 ADB capture.
- HyperOS may throw `SecurityException` on `Settings.Global.getString` and report misleading `Build` fields; `runCatching` + narrow-rule/fingerprint matching (already in `DeviceMatch`) mitigate — verify on Redmi Pad Pro 5G (Android 16 / HyperOS 3).
- `CapabilitySnapshotStore.reset()` must be wired to `MirrorService.stop()` teardown; forgetting it yields stale caps after a privilege provider change mid-session.
- Requires ARB key addition + `NativeStrings.kt` regeneration (generated file) before reason strings resolve; falling back to the key literal is acceptable interim behavior.
