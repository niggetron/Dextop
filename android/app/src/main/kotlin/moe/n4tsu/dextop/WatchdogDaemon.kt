package moe.n4tsu.dextop

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Standalone rollback watchdog, launched as a detached shell `app_process`.
 * No Android Context and no Looper: it only speaks CRC-framed JSON over an
 * abstract LocalServerSocket and executes allowlisted `settings`/`wm` argv.
 */
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
            LocalServerSocket("@dextop.wd.$appUid")
        } catch (error: IOException) {
            return // a live daemon already owns this UID's name
        }
        File(RECEIPT_DIR).mkdirs()
        startSupervisor()
        while (true) {
            val client: LocalSocket = try {
                server.accept()
            } catch (error: IOException) {
                continue
            }
            handle(client, appUid, token)
            runCatching { client.close() }
        }
    }

    private fun handle(client: LocalSocket, appUid: Int, token: String) {
        var authenticated = false
        try {
            val message = WatchdogProtocol.read(client.inputStream)
            when (message.optString("type")) {
                "HELLO" -> {
                    val peerUid = client.peerCredentials.uid
                    if (message.optString("token") == token && peerUid == appUid) {
                        authenticated = true
                        WatchdogProtocol.write(
                            client.outputStream,
                            WatchdogProtocol.envelope("HELLO_ACK")
                                .put("nonce", message.optString("nonce")),
                        )
                    }
                }
                "ARM" -> if (authenticated) {
                    val seq = message.optLong("armSeq", -1L)
                    val sid = message.optString("sessionId")
                    if (seq >= armSeq && !hasReceipt(sid)) {
                        val actions = WatchdogProtocol.actionsFromJson(message.optJSONArray("actions"))
                        actions.forEach { WatchdogProtocol.validate(it.argv) }
                        armSeq = seq
                        sessionId = sid
                        snapshot = actions
                        watchedPid = message.optInt("pid", -1)
                        watchedStartTicks = message.optLong("pidStartTicks", -1L)
                        deadlineMs = System.currentTimeMillis() +
                            message.optLong("deadlineMs", DEFAULT_DEADLINE_MS)
                        dead.set(!isAlive(watchedPid, watchedStartTicks))
                        armed = true
                    }
                }
                "HEARTBEAT" -> if (authenticated && message.optLong("armSeq", -1L) == armSeq) {
                    deadlineMs = System.currentTimeMillis() + HEARTBEAT_WINDOW_MS
                }
                "ACK" -> if (authenticated && message.optString("sessionId") == sessionId) {
                    writeReceipt(sessionId)
                    armed = false
                    WatchdogProtocol.write(client.outputStream, WatchdogProtocol.envelope("ACKED"))
                    exitProcess(0)
                }
            }
        } catch (error: Throwable) {
            // Malformed, CRC-failed or unauthorized frame: ignore, keep serving.
        }
    }

    /** Independent of accept(): a stuck client must not disarm the deadline. */
    private fun startSupervisor() {
        Thread({
            while (true) {
                try {
                    if (armed) {
                        if (watchedPid > 0 && !isAlive(watchedPid, watchedStartTicks)) dead.set(true)
                        if (dead.get() || System.currentTimeMillis() > deadlineMs) fireAndExit()
                    }
                    Thread.sleep(100)
                } catch (error: Throwable) {
                    // Keep supervising.
                }
            }
        }, "dextop-watchdog-supervisor").start()
    }

    /** /proc/<pid>/stat field 22 (starttime) = index 19 after the final ')'. */
    private fun isAlive(pid: Int, startTicks: Long): Boolean {
        if (pid <= 0 || startTicks <= 0L) return false
        val stat = runCatching {
            File("/proc/$pid/stat").readText().substringAfterLast(')').trim()
        }.getOrNull() ?: return false
        val current = stat.split(' ').getOrNull(19)?.toLongOrNull() ?: return false
        return current == startTicks
    }

    private fun fireAndExit() {
        if (!fired.compareAndSet(false, true)) return
        armed = false
        for (action in snapshot.asReversed()) {
            if (action.handledByApp || action.argv.isEmpty()) continue
            runCatching {
                val command = ArrayList<String>(action.argv.size + 1)
                command.add("/system/bin/" + action.argv[0])
                command.addAll(action.argv.drop(1))
                ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
            }
        }
        exitProcess(0)
    }

    private fun receipt(sessionId: String): File = File(RECEIPT_DIR, "$sessionId.ack")

    private fun hasReceipt(sessionId: String): Boolean =
        sessionId.isNotEmpty() && receipt(sessionId).exists()

    private fun writeReceipt(sessionId: String) {
        if (sessionId.isEmpty()) return
        runCatching {
            receipt(sessionId).apply {
                parentFile.mkdirs()
                writeText("ACKED\n")
            }
        }
    }
}
