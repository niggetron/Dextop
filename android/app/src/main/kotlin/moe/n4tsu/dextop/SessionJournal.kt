package moe.n4tsu.dextop

import android.app.WallpaperManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32

/** Namespace of a journaled system setting. */
internal enum class JournalScope(val shellName: String) {
    GLOBAL("global"), SYSTEM("system"), SECURE("secure")
}

/** The value a setting had before Dextop changed it. */
internal data class JournalBefore(val present: Boolean, val value: String?)

/**
 * Crash-safe session journal.
 *
 * Every mutation is appended to an NDJSON write-ahead log and `fsync()`ed BEFORE
 * the corresponding Settings/WindowManager change. On the next launch a torn
 * tail (bad CRC or sequence gap) is discarded, all recorded mutations are
 * restored in reverse order, and only a verified restore commits the journal.
 * This replaces the old SharedPreferences journal which restored only 3 of the
 * 7 Android 16 `modernKeys` (BUG-01).
 */
internal class SessionJournal(private val context: Context) {
    private val directory = File(context.filesDir, "dextop_session")
    private val walFile = File(directory, "session_journal.wal")
    private val privileged = PrivilegedAccess("DextopSessionRecovery")
    private val watchdog by lazy { WatchdogClient(context, PrivilegedAccess("DextopWatchdog")) }
    private var state = blankState()
    private var sequence = 0L

    init {
        directory.mkdirs()
        recover()
    }

    // ------------------------------------------------------------------ public API

    /** Begin a session if one is not already open (idempotent). */
    fun preparing(width: Int, height: Int, density: Int, decorations: Boolean) {
        if (state.optString("phase", "idle") != "idle") return
        val overlay = Settings.Global.getString(
            context.contentResolver, DisplayMirrorBackend.DISPLAY_SPECIFICATION
        )
        val wallpaper = WallpaperManager.getInstance(context)
        val mode = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.mode
        val wallWidth = wallpaper.desiredMinimumWidth.takeIf { it > 0 }
            ?: mode?.physicalWidth ?: context.resources.displayMetrics.widthPixels
        val wallHeight = wallpaper.desiredMinimumHeight.takeIf { it > 0 }
            ?: mode?.physicalHeight ?: context.resources.displayMetrics.heightPixels
        val initial = JSONArray()
            .put(JSONObject()
                .put("id", "overlay").put("kind", "overlay")
                .put("present", overlay != null)
                .put("value", overlay ?: JSONObject.NULL))
            .put(JSONObject()
                .put("id", "wallpaper").put("kind", "wallpaper")
                .put("width", wallWidth).put("height", wallHeight))
        val sessionId = UUID.randomUUID().toString()
        append("begin", JSONObject()
            .put("sessionId", sessionId)
            .put("width", width).put("height", height).put("density", density)
            .put("decorations", decorations)
            .put("mutations", initial), reset = true)
        armWatchdog(sessionId)
    }

    /** Translate the journal's recorded mutations into allowlisted shell argv. */
    private fun watchdogActions(): List<WatchdogProtocol.RestoreAction> = buildList {
        val mutations = mutations()
        for (index in 0 until mutations.length()) {
            val record = mutations.getJSONObject(index)
            when (record.optString("kind")) {
                "setting" -> {
                    val scope = record.optString("namespace", "global")
                    val key = record.optString("key")
                    val value = if (record.isNull("value")) null else record.optString("value")
                    val argv = if (value == null) {
                        listOf("settings", "delete", scope, key)
                    } else {
                        listOf("settings", "put", scope, key, value)
                    }
                    if (runCatching { WatchdogProtocol.validate(argv) }.isSuccess) {
                        add(WatchdogProtocol.RestoreAction("setting", argv))
                    }
                }
                "wm" -> {
                    val displayId = record.optInt("displayId")
                    val command = record.optString("command", "size")
                    val value = record.optString("value", "reset")
                    val argv = if (value == "reset") {
                        listOf("wm", command, "reset", "-d", displayId.toString())
                    } else {
                        listOf("wm", command, value, "-d", displayId.toString())
                    }
                    if (runCatching { WatchdogProtocol.validate(argv) }.isSuccess) {
                        add(WatchdogProtocol.RestoreAction("wm", argv))
                    }
                }
                "overlay" -> {
                    val value = if (record.isNull("value")) null else record.optString("value")
                    val argv = if (value == null) {
                        listOf("settings", "delete", "global", DisplayMirrorBackend.DISPLAY_SPECIFICATION)
                    } else {
                        listOf("settings", "put", "global", DisplayMirrorBackend.DISPLAY_SPECIFICATION, value)
                    }
                    if (runCatching { WatchdogProtocol.validate(argv) }.isSuccess) {
                        add(WatchdogProtocol.RestoreAction("overlay", argv))
                    }
                }
                "wallpaper" -> add(WatchdogProtocol.RestoreAction("wallpaper", emptyList(), true))
            }
        }
    }

    private fun armWatchdog(id: String) {
        runCatching { watchdog.arm(id, watchdogActions()) }
            .onFailure { Log.w("DextopSessionJournal", "watchdog arm failed", it) }
    }

    /** MUST be called before a Settings mutation; first writer for a key wins. */
    fun rememberGlobal(key: String, value: String?) =
        recordSetting(JournalScope.GLOBAL, key, JournalBefore(value != null, value), null)

    fun rememberSystem(key: String, value: String?) =
        recordSetting(JournalScope.SYSTEM, key, JournalBefore(value != null, value), null)

    fun recordSetting(scope: JournalScope, key: String, before: JournalBefore, after: String?) {
        if (state.optString("phase", "idle") == "idle") {
            // Legacy callers may snapshot outside a session; open an implicit one.
            preparing(0, 0, 0, false)
        }
        val id = "${scope.shellName}:$key"
        if (has(id)) return
        append("mutate", JSONObject()
            .put("id", id).put("kind", "setting")
            .put("namespace", scope.shellName).put("key", key)
            .put("present", before.present)
            .put("value", if (before.present && before.value != null) before.value else JSONObject.NULL)
            .put("after", after ?: JSONObject.NULL))
    }

    /** Record the original value of a forced `wm size`/`wm density` before changing it. */
    fun recordWm(displayId: Int, command: String, before: String, after: String) {
        if (state.optString("phase", "idle") == "idle") preparing(0, 0, 0, false)
        val id = "wm:$command:$displayId"
        if (has(id)) return
        append("mutate", JSONObject()
            .put("id", id).put("kind", "wm")
            .put("displayId", displayId).put("command", command)
            .put("value", before).put("after", after))
    }

    fun rememberPhoneRotation(frozen: Boolean, rotation: Int) {
        if (state.optString("phase", "idle") == "idle") preparing(0, 0, 0, false)
        if (has("rotation")) return
        append("mutate", JSONObject()
            .put("id", "rotation").put("kind", "rotation")
            .put("frozen", frozen).put("rotation", rotation))
    }

    fun phoneRotationState(): PhoneRotationState? {
        val record = find("rotation") ?: return null
        return PhoneRotationState(
            record.optBoolean("frozen", false), record.optInt("rotation", 0)
        )
    }

    fun running(displayId: Int) =
        append("phase", JSONObject().put("phase", "running").put("displayId", displayId))

    fun paused() =
        append("phase", JSONObject().put("phase", "paused").put("displayId", -1))

    /** Restore every recorded mutation; keeps the journal open on any failure. */
    fun restoreSystemSettings(): Boolean = restoreAll()

    fun restoreAll(onRestoreExtras: () -> Boolean = { true }): Boolean {
        if (state.optString("phase", "idle") == "idle") {
            onRestoreExtras()
            return true
        }
        append("phase", JSONObject().put("phase", "restoring")
            .put("displayId", state.optInt("displayId", -1)))
        val mutations = mutations()
        var ok = true
        for (index in mutations.length() - 1 downTo 0) {
            val record = mutations.getJSONObject(index)
            if (record.optString("kind") == "rotation") continue
            ok = restoreOne(record) && ok
        }
        ok = onRestoreExtras() && ok
        if (!ok) {
            OperationLog.w(context, "SessionJournal",
                "restore incomplete; journal kept for retry pending=${mutations.length()}")
            return false
        }
        val sessionId = state.optString("sessionId", "")
        append("commit", JSONObject().put("restoredAtMs", System.currentTimeMillis()))
        runCatching { watchdog.ack(sessionId) }
            .onFailure { Log.w("DextopSessionJournal", "watchdog ack failed", it) }
        return true
    }

    /** Kept for callers that restore a specific system-key subset. */
    fun restoreSystemKeys(keys: Collection<String>) {
        keys.forEach { key ->
            val record = find("system:$key") ?: return@forEach
            restoreOne(record)
        }
    }

    fun discardSystemKeys(keys: Collection<String>) {
        keys.forEach { key -> remove("system:$key") }
    }

    /** Re-sends pre-session wallpaper dimensions after a virtual display disappears. */
    fun restoreWallpaperDimensions(): Boolean {
        val record = find("wallpaper") ?: return true
        return runCatching {
            WallpaperManager.getInstance(context).suggestDesiredDimensions(
                record.getInt("width"), record.getInt("height")
            )
            true
        }.onFailure {
            Log.e("DextopSessionJournal", "wallpaper dimension restoration failed", it)
        }.getOrDefault(false)
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "recoverable" to (state.optString("phase", "idle") != "idle"),
        "recovered" to (state.optString("phase", "idle") != "idle"),
        "transactionOpen" to (state.optString("phase", "idle") != "idle"),
        "phase" to state.optString("phase", "idle"),
        "width" to state.optInt("width", 0),
        "height" to state.optInt("height", 0),
        "density" to state.optInt("density", 0),
        "decorations" to state.optBoolean("decorations", false),
        "updatedAt" to state.optLong("updatedAtMs", 0L),
        "sessionId" to state.optString("sessionId", ""),
        "mutationCount" to mutations().length(),
        "version" to 1
    )

    fun clear() = synchronized(this) { writeTerminal() }

    // ------------------------------------------------------------------ WAL internals

    private fun blankState(): JSONObject = JSONObject()
        .put("version", 1).put("phase", "idle").put("mutations", JSONArray())

    private fun mutations(): JSONArray = state.optJSONArray("mutations") ?: JSONArray()

    private fun has(id: String): Boolean =
        (0 until mutations().length()).any { mutations().getJSONObject(it).optString("id") == id }

    private fun find(id: String): JSONObject? =
        (0 until mutations().length())
            .map { mutations().getJSONObject(it) }
            .firstOrNull { it.optString("id") == id }

    private fun remove(id: String) {
        val records = mutations()
        for (index in records.length() - 1 downTo 0) {
            if (records.getJSONObject(index).optString("id") == id) records.remove(index)
        }
    }

    private fun append(operation: String, data: JSONObject, reset: Boolean = false) {
        val raw = SessionWalCodec.canonical(data)
        val record = JSONObject()
            .put("seq", ++sequence).put("op", operation)
            .put("ts", System.currentTimeMillis())
            .put("raw", raw)
            .put("crc", SessionWalCodec.crc32(raw))
        val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(this) {
            FileOutputStream(walFile, !reset).use { out ->
                out.write(bytes)
                out.fd.sync() // durability barrier BEFORE the platform mutation
            }
            runCatching { FileInputStream(directory).use { it.fd.sync() } }
            applyEvent(record, data)
        }
        if (operation == "mutate") {
            armWatchdog(state.optString("sessionId", ""))
        }
    }

    private fun applyEvent(record: JSONObject, data: JSONObject) {
        val timestamp = record.optLong("ts", System.currentTimeMillis())
        when (record.optString("op")) {
            "begin" -> {
                state = blankState()
                data.keys().forEach { key ->
                    if (key != "mutations") state.put(key, data.get(key))
                }
                state.put("mutations", data.optJSONArray("mutations") ?: JSONArray())
            }
            "phase" -> {
                state.put("phase", data.optString("phase", "idle"))
                state.put("displayId", data.optInt("displayId", -1))
            }
            "mutate" -> mutations().put(data)
            "commit" -> state = blankState()
            else -> Unit // forward-compatible: unknown operations are ignored
        }
        state.put("updatedAtMs", timestamp)
    }

    private fun recover() {
        state = blankState()
        sequence = 0
        if (!walFile.exists()) return
        runCatching {
            walFile.forEachLine { line ->
                val record = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val raw = record.optString("raw")
                if (raw.isBlank() || SessionWalCodec.crc32(raw) != record.optLong("crc", -1)) {
                    return@forEachLine
                }
                val seq = record.optLong("seq", -1)
                if (seq != sequence + 1) return@forEachLine
                sequence = seq
                runCatching { applyEvent(record, JSONObject(raw)) }
            }
        }.onFailure {
            Log.w("DextopSessionJournal", "journal replay stopped", it)
        }
    }

    private fun writeTerminal() {
        sequence++
        val raw = SessionWalCodec.canonical(JSONObject().put("phase", "idle"))
        val record = JSONObject().put("seq", sequence).put("op", "commit")
            .put("ts", System.currentTimeMillis()).put("raw", raw)
            .put("crc", SessionWalCodec.crc32(raw))
        FileOutputStream(walFile, false).use { out ->
            out.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        runCatching { FileInputStream(directory).use { it.fd.sync() } }
        state = blankState()
    }

    private fun restoreOne(record: JSONObject): Boolean = when (record.optString("kind")) {
        "setting" -> {
            val scope = JournalScope.valueOf(record.getString("namespace").uppercase())
            val key = record.getString("key")
            val before = if (record.isNull("value")) null else record.getString("value")
            val written = writeSetting(scope, key, before)
            written && readSetting(scope, key) == before
        }
        "wm" -> {
            val value = record.optString("value", "reset")
            val command = record.optString("command", "size")
            val displayId = record.optInt("displayId")
            val args = if (value == "reset") {
                arrayOf("wm", command, "reset", "-d", displayId.toString())
            } else {
                arrayOf("wm", command, value, "-d", displayId.toString())
            }
            privileged.execute(*args).succeeded
        }
        "overlay" -> {
            val before = if (record.isNull("value")) null else record.getString("value")
            runCatching {
                Settings.Global.putString(
                    context.contentResolver, DisplayMirrorBackend.DISPLAY_SPECIFICATION, before
                )
            }.getOrDefault(false)
        }
        "wallpaper" -> true // handled by restoreWallpaperDimensions
        else -> true
    }

    private fun writeSetting(scope: JournalScope, key: String, value: String?): Boolean {
        if (privileged.isAvailable()) {
            val result = if (value == null) {
                privileged.execute("settings", "delete", scope.shellName, key)
            } else {
                privileged.execute("settings", "put", scope.shellName, key, value)
            }
            if (result.succeeded) return true
        }
        return runCatching {
            when (scope) {
                JournalScope.GLOBAL -> Settings.Global.putString(context.contentResolver, key, value)
                JournalScope.SYSTEM -> Settings.System.putString(context.contentResolver, key, value)
                JournalScope.SECURE -> false
            }
        }.getOrDefault(false)
    }

    private fun readSetting(scope: JournalScope, key: String): String? {
        val viaShell = if (privileged.isAvailable()) {
            privileged.execute("settings", "get", scope.shellName, key)
                .takeIf { it.succeeded }?.output?.trim()
                ?.takeUnless { it.isBlank() || it == "null" }
        } else null
        return viaShell ?: runCatching {
            when (scope) {
                JournalScope.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
                JournalScope.SYSTEM -> Settings.System.getString(context.contentResolver, key)
                JournalScope.SECURE -> null
            }
        }.getOrNull()
    }
}

/** Pure JVM-testable canonical JSON + CRC codec. */
internal object SessionWalCodec {
    fun crc32(raw: String): Long {
        val crc = CRC32()
        crc.update(raw.toByteArray(Charsets.UTF_8))
        return crc.value
    }

    fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            "\"$key\":${canonical(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") {
            canonical(value.get(it))
        }
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
