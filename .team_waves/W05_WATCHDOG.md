# W5A (M2) — Detached Watchdog Implementation

## Scope
W04 decision kept (detached shell `app_process`, abstract `LocalServerSocket`, CRC-framed org.json, stdin token, `/proc` starttime poll — no `PR_SET_PDEATHSIG`). Verified against live sources: `PrivilegedAccess.kt:25-41`, `DistributionPrivilegeRuntime.kt:74-91`, `EmbeddedPrivilegeProtocol.kt:10`, `EmbeddedServerPayload.kt:16-24`, `PublisherKeys.kt:23-28`, `SessionJournal.kt:222-238/296-325/362-380`, `proguard-rules.pro:1-10`. No builds, no source edits, no token touched.

## Deliverable — full file contents

### 1. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogProtocol.kt`
```kotlin
package moe.n4tsu.dextop

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Wire format: [4-byte LE payload length][UTF-8 JSON][CRC32(payload), 4-byte LE]. */
internal object WatchdogProtocol {
    const val MAGIC = "DW01"
    const val MAX_FRAME = 256 * 1024
    private val LE = ByteOrder.LITTLE_ENDIAN
    private val KEY = Regex("[a-z0-9_]+")
    private val SAFE_VALUE = Regex("[A-Za-z0-9_.:/+=, \\-]+")
    private val BAD = Regex("[;&|\\\$`\"'(){}<>\n\r]")

    data class RestoreAction(
        val kind: String,
        val argv: List<String>,
        val handledByApp: Boolean = false
    ) {
        fun toJson(): JSONObject {
            val arr = JSONArray()
            argv.forEach { arr.put(it) }
            return JSONObject()
                .put("kind", kind)
                .put("argv", arr)
                .put("handledByApp", handledByApp)
        }

        companion object {
            fun from(o: JSONObject): RestoreAction {
                val a = o.optJSONArray("argv")
                val argv = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
                return RestoreAction(
                    o.optString("kind", ""), argv, o.optBoolean("handledByApp", false)
                )
            }
        }
    }

    fun envelope(type: String): JSONObject = JSONObject().put("magic", MAGIC).put("type", type)

    fun encode(json: JSONObject): ByteArray {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        check(payload.isNotEmpty() && payload.size <= MAX_FRAME) { "frame too large" }
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
        val len = ByteBuffer.wrap(readFully(input, 4)).order(LE).int
        check(len in 1..MAX_FRAME) { "bad frame length" }
        val rest = readFully(input, len + 4)
        val payload = rest.copyOf(len)
        val crc = ByteBuffer.wrap(rest, len, 4).order(LE).int
        check(CRC32().apply { update(payload) }.value.toInt() == crc) { "bad frame CRC" }
        val json = JSONObject(String(payload, Charsets.UTF_8))
        check(json.optString("magic") == MAGIC) { "bad magic" }
        return json
    }

    fun actionsToJson(actions: List<RestoreAction>): JSONArray {
        val a = JSONArray()
        actions.forEach { a.put(it.toJson()) }
        return a
    }

    fun actionsFromJson(a: JSONArray?): List<RestoreAction> =
        (0 until (a?.length() ?: 0)).map { RestoreAction.from(a!!.getJSONObject(it)) }

    /** Typed argv allowlist: no shell is involved, but keep argv inert and auditable. */
    fun validate(argv: List<String>) {
        check(argv.isNotEmpty()) { "empty argv" }
        check(argv.none { BAD.containsMatchIn(it) }) { "shell metacharacter in argv" }
        when (argv[0]) {
            "settings" -> {
                check(argv.size in 4..5) { "settings arity" }
                check(argv[1] in setOf("put", "delete", "get")) { "settings verb" }
                check(argv[2] in setOf("global", "system", "secure")) { "settings scope" }
                check(KEY.matches(argv[3])) { "settings key" }
                if (argv.size == 5) check(SAFE_VALUE.matches(argv[4])) { "settings value" }
            }
            "wm" -> {
                check(argv.size in 2..4) { "wm arity" }
                check(argv[1] in setOf("size", "density")) { "wm verb" }
                check(argv.drop(1).all { it == "reset" || it == "-d" || SAFE_VALUE.matches(it) }) {
                    "wm argument"
                }
            }
            else -> error("argv[0] not allowlisted: ${argv[0]}")
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IOException("unexpected EOF")
            off += r
        }
        return out
    }
}
```

### 2. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogDaemon.kt`
Runs standalone via `app_process`; entry point is `public static main` (`@JvmStatic`). No Android `Context`, no `Looper`, no androidx/`@Keep` (R8 handled by proguard rule below).
```kotlin
package moe.n4tsu.dextop

import android.net.LocalServerSocket
import android.net.LocalSocket
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object WatchdogDaemon {
    private const val RECEIPT_DIR = "/data/local/tmp/dextop/watchdog"
    private const val DEFAULT_DEADLINE_MS = 15_000L
    private const val HEARTBEAT_WINDOW_MS = 15_000L

    @Volatile private var armed = false
    @Volatile private var deadlineMs = Long.MAX_VALUE
    @Volatile private var armSeq = -1L
    @Volatile private var sessionId = ""
    @Volatile private var watchedPid = -1
    @Volatile private var watchedStartTicks = -1L
    @Volatile private var snapshot: List<WatchdogProtocol.RestoreAction> = emptyList()
    private val dead = AtomicBoolean(false)
    private val fired = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) return
        val appUid = args[0].toIntOrNull() ?: return
        val token = System.`in`.bufferedReader().use { it.readLine() } ?: return
        val server = try {
            LocalServerSocket("@dextop.wd.$appUid") // abstract: no stale socket file; single winner
        } catch (e: IOException) {
            return // a live daemon already owns this UID's name
        }
        File(RECEIPT_DIR).mkdirs()

        startSupervisor()
        while (true) {
            val client: LocalSocket = try {
                server.accept()
            } catch (e: IOException) {
                continue
            }
            handle(client, appUid, token)
            runCatching { client.close() }
        }
    }

    private fun handle(client: LocalSocket, appUid: Int, token: String) {
        var authenticated = false
        try {
            val msg = WatchdogProtocol.read(client.inputStream)
            when (msg.optString("type")) {
                "HELLO" -> {
                    val peerUid = client.peerCredentials.uid
                    if (msg.optString("token") == token && peerUid == appUid) {
                        authenticated = true
                        val reply = WatchdogProtocol.envelope("HELLO_ACK")
                            .put("nonce", msg.optString("nonce"))
                        WatchdogProtocol.write(client.outputStream, reply)
                    }
                }
                "ARM" -> if (authenticated) {
                    val seq = msg.optLong("armSeq", -1L)
                    val sid = msg.optString("sessionId")
                    if (seq >= armSeq && !hasReceipt(sid)) {
                        val actions = WatchdogProtocol.actionsFromJson(msg.optJSONArray("actions"))
                        actions.forEach { WatchdogProtocol.validate(it.argv) }
                        armSeq = seq
                        sessionId = sid
                        snapshot = actions
                        watchedPid = msg.optInt("pid", -1)
                        watchedStartTicks = msg.optLong("pidStartTicks", -1L)
                        deadlineMs = System.currentTimeMillis() +
                            msg.optLong("deadlineMs", DEFAULT_DEADLINE_MS)
                        dead.set(!isAlive(watchedPid, watchedStartTicks))
                        armed = true
                    }
                }
                "HEARTBEAT" -> if (authenticated && msg.optLong("armSeq", -1L) == armSeq) {
                    deadlineMs = System.currentTimeMillis() + HEARTBEAT_WINDOW_MS
                }
                "ACK" -> if (authenticated && msg.optString("sessionId") == sessionId) {
                    writeReceipt(sessionId)
                    armed = false
                    WatchdogProtocol.write(client.outputStream, WatchdogProtocol.envelope("ACKED"))
                    exitProcess(0)
                }
            }
        } catch (t: Throwable) {
            // Malformed/CRC-failed/unauthorized frame: ignore, keep serving.
        }
    }

    /** Independent of accept(): a stuck client must not disarm the deadline. */
    private fun startSupervisor() {
        Thread({
            while (true) {
                try {
                    if (armed) {
                        if (watchedPid > 0 && !isAlive(watchedPid, watchedStartTicks)) dead.set(true)
                        if (dead.get() || System.currentTimeMillis() > deadlineMs) {
                            fireAndExit()
                        }
                    }
                    Thread.sleep(100)
                } catch (t: Throwable) {
                    // keep supervising
                }
            }
        }, "dextop-watchdog-supervisor").start()
    }

    /** /proc/<pid>/stat: field 22 (starttime) = index 19 after the final ')'. */
    private fun isAlive(pid: Int, startTicks: Long): Boolean {
        if (pid <= 0 || startTicks <= 0L) return false
        val stat = runCatching {
            File("/proc/$pid/stat").readText().substringAfterLast(')').trim()
        }.getOrNull() ?: return false
        val fields = stat.split(' ')
        val now = fields.getOrNull(19)?.toLongOrNull() ?: return false
        return now == startTicks
    }

    private fun fireAndExit() {
        if (!fired.compareAndSet(false, true)) return
        armed = false
        for (action in snapshot.asReversed()) {
            if (action.handledByApp || action.argv.isEmpty()) continue
            runCatching {
                val cmd = ArrayList<String>(action.argv.size + 1)
                cmd.add("/system/bin/" + action.argv[0])
                cmd.addAll(action.argv.drop(1))
                ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor()
            }
        }
        exitProcess(0)
    }

    private fun receipt(sessionId: String): File = File(RECEIPT_DIR, sessionId + ".ack")

    private fun hasReceipt(sessionId: String): Boolean =
        sessionId.isNotEmpty() && receipt(sessionId).exists()

    private fun writeReceipt(sessionId: String) {
        if (sessionId.isEmpty()) return
        runCatching { receipt(sessionId).apply { parentFile.mkdirs(); writeText("ACKED\n") } }
    }
}
```

### 3. `android/app/src/main/kotlin/moe/n4tsu/dextop/WatchdogClient.kt`
```kotlin
package moe.n4tsu.dextop

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import moe.n4tsu.dextop.privilege.PublisherKeys
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal class WatchdogClient(
    private val context: Context,
    private val privileged: PrivilegedAccess
) : AutoCloseable {
    private val appUid = Process.myUid()
    private val token = PublisherKeys.issue(context, "watchdog")
    private val nonce = UUID.randomUUID().toString()
    private var socket: LocalSocket? = null
    private var connected = false
    private var armSeq = 0L
    private var sessionId = ""

    private fun shellQuote(v: String) = "'" + v.replace("'", "'\\''") + "'"

    fun start() {
        if (connected) return
        val apk = shellQuote(context.applicationInfo.sourceDir)
        val cmd = "IFS= read -r key; echo \"\$key\" | CLASSPATH=$apk " +
            "nohup /system/bin/setsid /system/bin/app_process /system/bin " +
            "--nice-name=${context.packageName}:dextop_watchdog " +
            "moe.n4tsu.dextop.WatchdogDaemon $appUid " +
            "> /data/local/tmp/dextop-watchdog.log 2>&1 & echo DEXTOP_WD_PID=\$!"
        privileged.executeWithInput(token, "/system/bin/sh", "-c", cmd)
        connect()
    }

    private fun connect() {
        val s = LocalSocket()
        s.connect(
            LocalSocketAddress("@dextop.wd.$appUid", LocalSocketAddress.Namespace.ABSTRACT)
        )
        WatchdogProtocol.write(
            s.outputStream,
            WatchdogProtocol.envelope("HELLO")
                .put("token", token).put("nonce", nonce).put("uid", appUid)
        )
        val reply = WatchdogProtocol.read(s.inputStream)
        check(reply.optString("type") == "HELLO_ACK" && reply.optString("nonce") == nonce) {
            "watchdog HELLO failed"
        }
        socket = s
        connected = true
    }

    private fun send(json: JSONObject) {
        try {
            if (!connected) connect()
            WatchdogProtocol.write(socket!!.outputStream, json)
        } catch (e: IOException) {
            connected = false
            runCatching { socket?.close() }
            socket = null
            connect() // daemon may have been killed; re-arm the replacement
            WatchdogProtocol.write(socket!!.outputStream, json)
        }
    }

    fun arm(sessionId: String, actions: List<WatchdogProtocol.RestoreAction>) {
        start()
        this.sessionId = sessionId
        val seq = ++armSeq
        val pid = Process.myPid()
        val startTicks = File("/proc/self/stat").readText().substringAfterLast(')')
            .trim().split(' ').getOrNull(19)?.toLongOrNull() ?: -1L
        send(
            WatchdogProtocol.envelope("ARM")
                .put("sessionId", sessionId)
                .put("armSeq", seq)
                .put("pid", pid)
                .put("pidStartTicks", startTicks)
                .put("deadlineMs", 15_000L)
                .put("actions", WatchdogProtocol.actionsToJson(actions))
        )
    }

    fun heartbeat() {
        if (!connected) return
        send(WatchdogProtocol.envelope("HEARTBEAT").put("armSeq", armSeq))
    }

    fun ack(sessionId: String = this.sessionId) {
        if (!connected) return
        send(WatchdogProtocol.envelope("ACK").put("sessionId", sessionId))
        close()
    }

    override fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
    }
}
```

## Exact integration points (copy-ready)

**`PrivilegedAccess.kt`** — insert after `execute()` (line 41), uses existing `executeCommandWithInput` transact path in `DistributionPrivilegeRuntime.kt:74-86`:
```kotlin
    fun executeWithInput(input: String, vararg arguments: String): CommandResult = runCatching {
        DistributionPrivilegeRuntime.execute(arguments, input)?.let {
            return@runCatching CommandResult(it.exitCode, it.output, it.error)
        }
        val binder = Shizuku.getBinder()
            ?: error(NativeStrings.text("nativeShizukuUnavailable"))
        val process = IShizukuService.Stub.asInterface(binder)
            .newProcess(arguments, java.io.ByteArrayInputStream(input.toByteArray()), null)
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            .bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        CommandResult(exitCode, output.trim(), "")
    }.onFailure {
        Log.e(tag, "privileged command with input failed", it)
    }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }
```

**`SessionJournal.kt`**
- field near `:38`: `private val watchdog = WatchdogClient(context, PrivilegedAccess("DextopWatchdog"))`
- `:50-75` `preparing()` after `append("begin"…)`: `watchdog.arm(state.optString("sessionId"), actionsFromState())`
- `:222-238` end of `append()` inside `synchronized` block, only for `op` in `{"begin","mutate","phase"}` and phase not `"commit"`/`"idle"`: `watchdog.arm(state.optString("sessionId"), actionsFromState())`
- `:154` after successful `append("commit"…)`: `watchdog.ack(state.optString("sessionId"))`; `:198 clear()`: `watchdog.ack()` then `watchdog.close()`
- add private `actionsFromState(): List<WatchdogProtocol.RestoreAction>` mapping `mutations()` in order: `"setting"` → `settings put|delete <scope> <key> [value]` (omit value ⇒ delete); `"wm"` → `wm <command> reset|-d…` from `"value"`/`"displayId"`; `"overlay"` → `settings put global DISPLAY_SPECIFICATION <value>`; `"wallpaper"`/`"rotation"` → `RestoreAction(kind, emptyList(), handledByApp = true)`. Filter `handledByApp` for the app-side heartbeat only; daemon skips them.
- heartbeat: call `watchdog.heartbeat()` every 5 s from the FSM IO scope (`DesktopSessionFsm` scope), stop on teardown.

**`proguard-rules.pro`** — append:
```
-keep class moe.n4tsu.dextop.WatchdogDaemon { *; }
-keep class moe.n4tsu.dextop.WatchdogClient { *; }
-keep class moe.n4tsu.dextop.WatchdogProtocol { *; }
```
**`AndroidManifest.xml`**, `CMakeLists.txt`, `DesktopModeConfigurator.kt`, `MirrorService.kt`, `MainActivity.kt`: **no change**. No JNI, no new deps (Java death-poll only). `SessionWalCodec` unchanged.

## Risks
- `ProcessBuilder` executes `/system/bin/settings|wm` directly; hidden HyperOS keys may still reject writes — verify with `dumpsys`.
- Receipt dir `/data/local/tmp/dextop/watchdog` is shell-writable but world-readable; sessionIds are UUIDs, no secret stored.
- `nohup` keeps piped stdin (non-TTY) so the token reaches the daemon; validate on device.
- Heartbeats are fire-and-forget; if all ACK frames are lost the daemon fires once on deadline — acceptable (idempotent).
- No local compilation performed (GitHub Actions only); untested by compiler.
