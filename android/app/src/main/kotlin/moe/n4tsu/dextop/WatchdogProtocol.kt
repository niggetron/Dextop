package moe.n4tsu.dextop

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** CRC-framed JSON protocol for the detached rollback watchdog. */
internal object WatchdogProtocol {
    const val MAGIC = "DW01"
    const val MAX_FRAME = 256 * 1024
    private val LE = ByteOrder.LITTLE_ENDIAN
    private val KEY = Regex("[a-z0-9_]+")
    private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/+=,@ \\-]+")
    private val BAD = Regex("[;&|\\\$`\"'(){}<>\n\r]")

    data class RestoreAction(
        val kind: String,
        val argv: List<String>,
        val handledByApp: Boolean = false,
    ) {
        fun toJson(): JSONObject {
            val array = JSONArray()
            argv.forEach { array.put(it) }
            return JSONObject().put("kind", kind).put("argv", array)
                .put("handledByApp", handledByApp)
        }

        companion object {
            fun from(value: JSONObject): RestoreAction {
                val array = value.optJSONArray("argv")
                val argv = (0 until (array?.length() ?: 0)).map { array!!.optString(it) }
                return RestoreAction(
                    value.optString("kind", ""), argv,
                    value.optBoolean("handledByApp", false),
                )
            }
        }
    }

    fun envelope(type: String): JSONObject = JSONObject().put("magic", MAGIC).put("type", type)

    fun encode(json: JSONObject): ByteArray {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        check(payload.isNotEmpty() && payload.size <= MAX_FRAME)
        val crc = CRC32().apply { update(payload) }.value
        return ByteBuffer.allocate(4 + payload.size + 4).order(LE)
            .putInt(payload.size).put(payload).putInt(crc.toInt()).array()
    }

    fun write(out: OutputStream, json: JSONObject) {
        out.write(encode(json))
        out.flush()
    }

    @Throws(IOException::class)
    fun read(input: InputStream): JSONObject {
        val length = ByteBuffer.wrap(readFully(input, 4)).order(LE).int
        check(length in 1..MAX_FRAME)
        val rest = readFully(input, length + 4)
        val payload = rest.copyOf(length)
        val crc = ByteBuffer.wrap(rest, length, 4).order(LE).int
        check(CRC32().apply { update(payload) }.value.toInt() == crc)
        val json = JSONObject(String(payload, Charsets.UTF_8))
        check(json.optString("magic") == MAGIC)
        return json
    }

    fun actionsToJson(actions: List<RestoreAction>): JSONArray {
        val array = JSONArray()
        actions.forEach { array.put(it.toJson()) }
        return array
    }

    fun actionsFromJson(value: JSONArray?): List<RestoreAction> =
        (0 until (value?.length() ?: 0)).map { RestoreAction.from(value!!.getJSONObject(it)) }

    /** Typed argv allowlist: no shell is involved, but keep argv inert and auditable. */
    fun validate(argv: List<String>) {
        check(argv.isNotEmpty())
        check(argv.none { BAD.containsMatchIn(it) })
        when (argv[0]) {
            "settings" -> {
                check(argv.size in 4..5)
                check(argv[1] in setOf("put", "delete", "get"))
                check(argv[2] in setOf("global", "system", "secure"))
                check(KEY.matches(argv[3]))
                if (argv.size == 5) check(SAFE_VALUE.matches(argv[4]))
            }
            "wm" -> {
                check(argv.size in 2..5)
                check(argv[1] in setOf("size", "density"))
                check(argv.drop(1).all { it == "reset" || it == "-d" || SAFE_VALUE.matches(it) })
            }
            else -> error("argv=${argv[0]}")
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw IOException()
            offset += read
        }
        return result
    }
}
