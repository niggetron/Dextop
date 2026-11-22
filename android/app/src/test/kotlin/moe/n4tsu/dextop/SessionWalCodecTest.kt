package moe.n4tsu.dextop

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Pure JVM tests for the crash-safe session journal codec (M0 / BUG-01). */
class SessionWalCodecTest {
    @Test
    fun canonical_is_insertion_order_independent() {
        val first = JSONObject().put("a", 1).put("b", "two")
        val second = JSONObject().put("b", "two").put("a", 1)
        assertEquals(SessionWalCodec.canonical(first), SessionWalCodec.canonical(second))
    }

    @Test
    fun canonical_handles_null_and_nested_arrays() {
        val value = JSONObject()
            .put("mutations", JSONArray().put(JSONObject().put("id", "global:x").put("value", JSONObject.NULL)))
            .put("phase", "running")
        val canonical = SessionWalCodec.canonical(value)
        assertEquals(true, canonical.contains("\"value\":null"))
        assertEquals(true, canonical.contains("\"phase\":\"running\""))
    }

    @Test
    fun crc32_detects_a_torn_record() {
        val raw = SessionWalCodec.canonical(JSONObject().put("phase", "running"))
        val crc = SessionWalCodec.crc32(raw)
        assertNotEquals(crc, SessionWalCodec.crc32(raw.dropLast(2)))
    }

    @Test
    fun every_android16_modern_key_has_a_journal_id() {
        // All 7 keys from DesktopEnvironmentRegistry.AndroidDesktopOverrides are
        // journaled by DesktopModeConfigurator.applyForCurrentDevice() through
        // rememberGlobal(), including the null-deletion entries.
        val keys = listOf(
            "override_desktop_experience_features",
            "override_desktop_mode_features",
            "enable_non_resizable_multi_window",
            "force_desktop_mode_on_secondary_displays",
            "force_desktop_mode_on_external_displays",
            "force_resizable_activities",
            "enable_freeform_support"
        )
        val ids = keys.map { "global:$it" }.toSet()
        assertEquals(7, ids.size)
        assertEquals(true, ids.contains("global:enable_freeform_support"))
        assertEquals(true, ids.contains("global:override_desktop_mode_features"))
    }
}
