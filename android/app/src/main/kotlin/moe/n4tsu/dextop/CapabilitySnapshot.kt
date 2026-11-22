package moe.n4tsu.dextop

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import java.io.File

/** One read-only fact. A missing capability carries a stable reason string. */
internal data class Probe(
    val supported: Boolean,
    val reason: String? = null,
    val detail: String? = null,
) {
    companion object {
        fun yes(detail: String? = null) = Probe(true, null, detail)
        fun no(reason: String, detail: String? = null) = Probe(false, reason, detail)
    }
}

internal enum class BackendId { A, B, C, D, E, F }

/** Immutable, read-only view of what this device can actually do. */
internal data class CapabilitySnapshot(
    val identity: DeviceIdentity,
    val environmentId: String,
    val matchedRuleId: String?,
    val recommendedBackend: BackendId,
    val display: Map<String, Probe>,
    val windowing: Map<String, Probe>,
    val input: Map<String, Probe>,
    val graphics: Map<String, Probe>,
    val privilege: Map<String, Probe>,
    val capturedAtMillis: Long,
) {
    fun toReportLines(): List<String> = buildList {
        add("[CAPABILITY SNAPSHOT]")
        add("capturedAt: $capturedAtMillis")
        add("manufacturer: ${identity.manufacturer}")
        add("brand: ${identity.brand}")
        add("model: ${identity.model}")
        add("device: ${identity.device}")
        add("sdk: ${identity.sdk}")
        add("environmentId: $environmentId")
        add("matchedRuleId: ${matchedRuleId ?: "none"}")
        add("recommendedBackend: ${recommendedBackend.name}")
        section("DISPLAY", display)
        section("WINDOWING", windowing)
        section("INPUT", input)
        section("GRAPHICS", graphics)
        section("PRIVILEGE", privilege)
    }

    private fun MutableList<String>.section(name: String, values: Map<String, Probe>) {
        add("[$name]")
        values.forEach { (key, value) ->
            add("$key: ${if (value.supported) "supported" else "unsupported reason=${value.reason}"}" +
                (value.detail?.let { " detail=$it" } ?: ""))
        }
    }
}

/**
 * Side-effect-free capability probe engine. Uses only reflection metadata,
 * PackageManager, DisplayManager.displays, InputDevice, Settings.getString and
 * File.exists. It never creates a display, binds a service, writes a setting or
 * spawns a process. The result is cached once per session.
 */
internal class CapabilitySnapshotEngine(private val context: Context) {
    @Volatile
    private var cached: CapabilitySnapshot? = null

    fun snapshot(): CapabilitySnapshot = cached ?: synchronized(this) {
        cached ?: build().also { cached = it }
    }

    fun invalidate() {
        synchronized(this) { cached = null }
    }

    private fun classExists(name: String) = runCatching { Class.forName(name); true }.getOrDefault(false)

    private fun methodExists(className: String, method: String) = runCatching {
        Class.forName(className).methods.any { it.name == method }
    }.getOrDefault(false)

    private fun setting(key: String): String? = runCatching {
        Settings.Global.getString(context.contentResolver, key)
    }.getOrNull()

    private fun build(): CapabilitySnapshot {
        val identity = DeviceIdentity.current()
        val environment = DesktopEnvironmentRegistry.resolve(identity)
        val matchedRule = DeviceProfiles.rules.firstOrNull { it.match.matches(identity) }?.id
        val packageManager = context.packageManager
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val displays = displayManager?.displays.orEmpty()

        val display = linkedMapOf<String, Probe>()
        display["displayManager"] = if (displayManager != null) Probe.yes("displays=${displays.size}") else Probe.no("no_display_manager")
        display["trustedVirtualDisplay"] = Probe(
            supported = packageManager.hasSystemFeature("android.software.virtual_display_trusted"),
            reason = "oem_virtual_display_policy"
        )
        display["virtualDisplay"] = Probe(
            supported = packageManager.hasSystemFeature("android.software.virtual_display") || displays.isNotEmpty(),
            reason = "no_virtual_display"
        )
        display["overlayDisplaySetting"] = if (setting("overlay_display_devices") != null) {
            Probe.yes("setting_readable")
        } else Probe.no("overlay_setting_unavailable")
        display["maxRefreshRate"] = displays.maxOfOrNull { display ->
            display.getSupportedModes().maxOfOrNull { mode -> mode.getRefreshRate() }
                ?: display.getRefreshRate()
        }?.let { Probe.yes("${it}Hz") } ?: Probe.no("no_display_modes")

        val windowing = linkedMapOf<String, Probe>()
        windowing["freeform"] = Probe(
            supported = setting("enable_freeform_support") == "1" || environment.configureFreeformWindowing,
            reason = "freeform_disabled"
        )
        windowing["forceResizable"] = Probe(
            supported = setting("force_resizable_activities") == "1",
            reason = "resizability_locked"
        )
        windowing["cmdWindow"] = Probe(
            supported = classExists("android.view.IWindowManager") &&
                methodExists("android.view.IWindowManager", "setWindowingMode"),
            reason = "hidden_api_unavailable"
        )
        windowing["scopedDeviceProfile"] = if (matchedRule?.startsWith("xiaomi_") == true) {
            Probe.yes(matchedRule)
        } else Probe.no("generic_profile")

        val inputDevices = mutableListOf<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id)
            if (device != null) inputDevices.add(device)
        }
        val input = linkedMapOf<String, Probe>()
        input["multiTouch"] = Probe(
            supported = inputDevices.any { device ->
                device.getSources() and InputDevice.SOURCE_TOUCHSCREEN != 0 &&
                    device.getMotionRanges().any { range -> range.getMax() > range.getMin() }
            },
            reason = "no_multitouch_device"
        )
        input["externalMouse"] = Probe(
            supported = inputDevices.any { device ->
                device.getSources() and InputDevice.SOURCE_MOUSE != 0
            },
            reason = "no_mouse"
        )
        input["externalKeyboard"] = Probe(
            supported = inputDevices.any { device ->
                device.getSources() and InputDevice.SOURCE_KEYBOARD != 0
            },
            reason = "no_keyboard"
        )
        input["inputInjection"] = Probe(
            supported = methodExists("android.hardware.input.IInputManager", "injectInputEvent"),
            reason = "hidden_api_unavailable"
        )
        input["uinputNode"] = if (File("/dev/uinput").exists()) Probe.yes() else Probe.no("no_uinput_node")

        val graphics = linkedMapOf<String, Probe>()
        graphics["hardwareBuffer"] = if (classExists("android.hardware.HardwareBuffer")) Probe.yes() else Probe.no("no_hardware_buffer")
        graphics["aSurfaceControl"] = if (classExists("android.view.SurfaceControl")) Probe.yes() else Probe.no("no_surface_control")
        graphics["vulkan"] = if (packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
            Probe.yes()
        } else Probe.no("no_vulkan")
        graphics["turnipBundled"] = if (File(context.applicationInfo.nativeLibraryDir, "libvulkan_dextop_turnip.so").exists()) {
            Probe.yes()
        } else Probe.no("not_bundled")
        graphics["aChoreographer"] = if (classExists("android.view.Choreographer")) Probe.yes() else Probe.no("no_choreographer")

        val privilege = linkedMapOf<String, Probe>()
        val privileged = PrivilegedAccess("DextopCapabilityProbe")
        val diagnostics = DeviceDiagnostics(context).report()
        privilege["provider"] = Probe.yes(diagnostics["privilegeProviderName"]?.toString() ?: "none")
        privilege["shellExecute"] = if (privileged.isAvailable()) Probe.yes() else Probe.no("no_privileged_provider")
        privilege["writeSecureSettings"] = if (privileged.isAvailable()) Probe.yes() else Probe.no("no_write_secure_settings")

        val isScopedXiaomi = matchedRule == "xiaomi_redmi_pad_pro_5g"
        val backend = when {
            isScopedXiaomi -> BackendId.E
            Build.VERSION.SDK_INT >= 36 && environment.platformManaged -> BackendId.A
            environment.platformManaged -> BackendId.B
            privileged.isAvailable() -> BackendId.C
            else -> BackendId.F
        }

        return CapabilitySnapshot(
            identity = identity,
            environmentId = environment.id,
            matchedRuleId = matchedRule,
            recommendedBackend = backend,
            display = display,
            windowing = windowing,
            input = input,
            graphics = graphics,
            privilege = privilege,
            capturedAtMillis = System.currentTimeMillis(),
        )
    }
}
