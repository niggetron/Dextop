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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** App-side controller for the detached rollback watchdog. */
internal class WatchdogClient(
    private val context: Context,
    private val privileged: PrivilegedAccess,
) : AutoCloseable {
    private val appUid = Process.myUid()
    private val token by lazy { PublisherKeys.issue(context, "watchdog") }
    private val nonce = UUID.randomUUID().toString()
    private var socket: LocalSocket? = null
    private var connected = false
    private var armSequence = 0L
    private var sessionId = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    fun start() {
        if (connected) return
        val apk = shellQuote(context.applicationInfo.sourceDir)
        val command = "IFS= read -r key; echo \"\$key\" | CLASSPATH=$apk " +
            "nohup /system/bin/setsid /system/bin/app_process /system/bin " +
            "--nice-name=${context.packageName}:dextop_watchdog " +
            "moe.n4tsu.dextop.WatchdogDaemon $appUid " +
            "> /data/local/tmp/dextop-watchdog.log 2>&1 & echo DEXTOP_WD_PID=\$!"
        privileged.executeWithInput(token, "/system/bin/sh", "-c", command)
        connect()
    }

    private fun connect() {
        val client = LocalSocket()
        client.connect(
            LocalSocketAddress("@dextop.wd.$appUid", LocalSocketAddress.Namespace.ABSTRACT)
        )
        WatchdogProtocol.write(
            client.outputStream,
            WatchdogProtocol.envelope("HELLO")
                .put("token", token).put("nonce", nonce).put("uid", appUid),
        )
        val reply = WatchdogProtocol.read(client.inputStream)
        check(reply.optString("type") == "HELLO_ACK" && reply.optString("nonce") == nonce)
        socket = client
        connected = true
    }

    private fun send(message: JSONObject) {
        try {
            if (!connected) connect()
            WatchdogProtocol.write(socket!!.outputStream, message)
        } catch (error: IOException) {
            connected = false
            runCatching { socket?.close() }
            socket = null
            connect() // the daemon may have been killed; re-arm the replacement
            WatchdogProtocol.write(socket!!.outputStream, message)
        }
    }

    fun arm(id: String, actions: List<WatchdogProtocol.RestoreAction>) {
        start()
        sessionId = id
        val sequence = ++armSequence
        val pid = Process.myPid()
        val startTicks = File("/proc/self/stat").readText().substringAfterLast(')')
            .trim().split(' ').getOrNull(19)?.toLongOrNull() ?: -1L
        send(
            WatchdogProtocol.envelope("ARM")
                .put("sessionId", id)
                .put("armSeq", sequence)
                .put("pid", pid)
                .put("pidStartTicks", startTicks)
                .put("deadlineMs", 15_000L)
                .put("actions", WatchdogProtocol.actionsToJson(actions)),
        )
        // Keep the 15 s deadline alive for the whole session; without a
        // heartbeat a healthy desktop would be rolled back after 15 s.
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                heartbeat()
            }
        }
    }

    fun heartbeat() {
        if (!connected) return
        send(
            WatchdogProtocol.envelope("HEARTBEAT")
                .put("nonce", nonce)
                .put("armSeq", armSequence),
        )
    }

    fun ack(id: String = sessionId) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        send(WatchdogProtocol.envelope("ACK").put("sessionId", id))
        close()
    }

    override fun close() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        connected = false
        runCatching { socket?.close() }
        socket = null
    }
}
