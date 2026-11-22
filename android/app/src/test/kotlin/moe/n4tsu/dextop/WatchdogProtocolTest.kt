package moe.n4tsu.dextop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WatchdogProtocolTest {
    @Test
    fun frame_round_trip() {
        val message = WatchdogProtocol.envelope("ARM")
            .put("sessionId", "test")
            .put("actions", WatchdogProtocol.actionsToJson(listOf(
                WatchdogProtocol.RestoreAction("setting", listOf("settings", "put", "global", "enable_freeform_support", "0")),
                WatchdogProtocol.RestoreAction("wm", listOf("wm", "size", "reset", "-d", "3")),
            )))
        val output = ByteArrayOutputStream()
        WatchdogProtocol.write(output, message)
        val decoded = WatchdogProtocol.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals("ARM", decoded.optString("type"))
        assertEquals(2, decoded.optJSONArray("actions").length())
    }

    @Test
    fun crc_rejects_corrupted_frame() {
        val frame = WatchdogProtocol.encode(WatchdogProtocol.envelope("HELLO"))
        frame[10] = (frame[10].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalStateException> {
            WatchdogProtocol.read(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun validate_accepts_allowlisted_argv() {
        WatchdogProtocol.validate(listOf("settings", "put", "global", "enable_freeform_support", "1"))
        WatchdogProtocol.validate(listOf("wm", "size", "reset", "-d", "5"))
    }

    @Test
    fun validate_rejects_shell_metacharacters() {
        assertFailsWith<IllegalStateException> {
            WatchdogProtocol.validate(listOf("settings", "put", "global", "key", "1`reboot"))
        }
    }
}
