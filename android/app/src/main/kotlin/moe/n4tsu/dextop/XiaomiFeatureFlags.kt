package moe.n4tsu.dextop

import android.content.Context

/**
 * Xiaomi / HyperOS feature switches, read from the same SharedPreferences file
 * the Flutter layer writes. Keys carry the "flutter." prefix used throughout
 * the app. Every default keeps the Android 16 compatibility fixes enabled; the
 * switches exist so a bad OEM behaviour can be disabled without a new build.
 */
internal class XiaomiFeatureFlags(context: Context) {
    private val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    /** Restore freeform globals + per-display windowing mode on SDK 36+. */
    val forceFreeform: Boolean
        get() = prefs.getBoolean(KEY_FORCE_FREEFORM, true)

    /** Keep pinning forced display size/density on Xiaomi virtual displays. */
    val pinMetrics: Boolean
        get() = prefs.getBoolean(KEY_PIN_METRICS, true)

    /** Keep the direct Plasma display workaround when laptop mode is active. */
    val laptopDirectDisplay: Boolean
        get() = prefs.getBoolean(KEY_LAPTOP_DIRECT_DISPLAY, true)

    /** Touch injection strategy: "accessibility" | "raw" | "direct". */
    val touchMode: String
        get() = prefs.getString(KEY_TOUCH_MODE, "direct") ?: "direct"

    /** Android 16 topology parcel layout: "" (auto) | "float" | "int". */
    val topologyLayout: String
        get() = prefs.getString(KEY_TOPOLOGY_LAYOUT, "") ?: ""

    private companion object {
        const val KEY_FORCE_FREEFORM = "flutter.xiaomi_force_freeform"
        const val KEY_PIN_METRICS = "flutter.xiaomi_pin_metrics"
        const val KEY_LAPTOP_DIRECT_DISPLAY = "flutter.xiaomi_laptop_direct_display"
        const val KEY_TOUCH_MODE = "flutter.xiaomi_tablet_touch_mode"
        const val KEY_TOPOLOGY_LAYOUT = "flutter.xiaomi_topology_layout"
    }
}
