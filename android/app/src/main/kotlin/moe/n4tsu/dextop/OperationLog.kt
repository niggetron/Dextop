package moe.n4tsu.dextop

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Privacy-filtered rolling diagnostics log.
 *
 * SEGMENT_COUNT files of SEGMENT_BYTES each are kept in app-private storage. When
 * the active segment fills, the oldest segment is recycled (drop-oldest), so the
 * most recent history is never lost. All mutable writer state is confined to one
 * background thread. [tailSnapshot] exposes the last TAIL_LINES lines immediately
 * for crash/export paths, before the 400 ms flush has run.
 */
internal object OperationLog {
    private const val SEGMENT_COUNT = 4
    private const val SEGMENT_BYTES = 256_000L
    private const val TAIL_LINES = 200
    private const val FLUSH_DELAY_MS = 400L
    private const val SEGMENT_PREFIX = "dextop-log-"
    private const val SEGMENT_SUFFIX = ".log"
    private const val INDEX_FILE = "dextop-log-index"
    private const val CURRENT_FILE = "dextop-session-current.log"
    private const val LAST_FILE = "dextop-session-last.log"
    private const val LEGACY_FILE = "dextop-operation.log"

    private val writer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "dextop-operation-log").apply { isDaemon = true }
    }

    // Writer-thread only:
    private val pending = StringBuilder()
    private var pendingFile: File? = null
    private var flushScheduled = false

    // Caller-thread only (SimpleDateFormat is not thread-safe):
    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

    // Last N lines, available before the buffered flush (crash path).
    private val tail = ArrayDeque<String>(TAIL_LINES)

    private val SANITIZERS = listOf(
        Regex("(?i)(package|descriptor|serial|email|account|path|uri|url|host|endpoint|receiver|friendlyName|ssid|label|classpath|pid)=[^ ,}]+") to "$1=<redacted>",
        Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b") to "<email>",
        Regex("(?i)\\b(?:https?|content|file)://[^\\s,}]+") to "<uri>",
        Regex("(?i)(?<![A-Za-z0-9_])/(?:data|storage|sdcard|mnt)(?:/[^\\s,}]*)?") to "<path>",
        Regex("(?<![A-Za-z0-9_])(?:[A-Za-z][A-Za-z0-9_]*\\.){2,}[A-Za-z][A-Za-z0-9_]*(?:/[A-Za-z0-9_.$]+)?") to "<component>",
        Regex("(?i)(x|y|rel|pos)=-?[0-9.]+(?:,-?[0-9.]+)?") to "$1=<redacted>",
    )

    // ---------- public API ----------

    fun beginSession(context: Context, summary: String) {
        val dir = context.filesDir
        writer.execute {
            flush()
            migrateLegacy(dir)
            val active = activeSegment(dir)
            if (!active.exists() || active.length() == 0L) {
                active.appendText("DEXTOP SESSION LOG v1\n")
                // Keep the geometry revision ahead of timestamped events for old report parsers.
                active.appendText("geometryInvariant=${GeometryInvariant.discriminator(1)}\n")
            }
        }
        i(context, "Session", "started $summary")
        writer.execute { flush() }
    }

    fun finishSession(context: Context, restored: Boolean) {
        write(context, if (restored) "I" else "W", "Session", "finished restored=$restored")
        writer.execute { flush() }
    }

    fun i(context: Context, component: String, message: String) = write(context, "I", component, message)
    fun w(context: Context, component: String, message: String, error: Throwable? = null) =
        write(context, "W", component, "$message${error?.let { " error=${it.javaClass.simpleName}" } ?: ""}")
    fun e(context: Context, component: String, message: String, error: Throwable? = null) =
        write(context, "E", component, "$message${error?.let { " error=${it.javaClass.simpleName}" } ?: ""}")

    /** Already formatted and sanitized by DextopLog; logcat has been written by the caller. */
    internal fun appendFormatted(context: Context, level: String, component: String, sanitizedLine: String) {
        writer.execute { queueLine(context.filesDir, level, component, sanitizedLine) }
    }

    /** Complete text, oldest -> newest. Safe to call from any thread. */
    fun snapshot(context: Context): String = writer.submit<String> {
        flush()
        readAllSegments(context.filesDir)
    }.get()

    fun read(context: Context): String = snapshot(context)
    fun readLastSession(context: Context): String = snapshot(context)

    /** Unflushed tail for crash/export handlers; never blocks on the writer thread. */
    fun tailSnapshot(): String = synchronized(tail) { tail.joinToString("\n", postfix = "\n") }

    fun clear(context: Context) {
        val dir = context.filesDir
        writer.execute {
            pending.setLength(0)
            pendingFile = null
            synchronized(tail) { tail.clear() }
            segmentFiles(dir).forEach { it.delete() }
            File(dir, INDEX_FILE).delete()
            listOf(CURRENT_FILE, LAST_FILE, LEGACY_FILE).forEach { File(dir, it).delete() }
        }
    }

    // ---------- sanitizers ----------

    internal fun sanitizeForReport(value: String): String =
        SANITIZERS.fold(value) { text, (pattern, replacement) -> pattern.replace(text, replacement) }.take(2_000)

    /** Per-line sanitizing for whole documents; no whole-document truncation. */
    internal fun sanitizeDocument(text: String): String = text.lineSequence()
        .map { line -> SANITIZERS.fold(line) { t, (p, r) -> p.replace(t, r) }.take(2_000) }
        .joinToString("\n")

    // ---------- internals (writer thread unless noted) ----------

    private fun write(context: Context, level: String, component: String, rawMessage: String) {
        val message = sanitizeForReport(rawMessage)
        when (level) {
            "E" -> Log.e(component, message)
            "W" -> Log.w(component, message)
            else -> Log.i(component, message)
        }
        val dir = context.filesDir
        writer.execute { queueLine(dir, level, component, message) }
    }

    private fun queueLine(dir: File, level: String, component: String, message: String) {
        val line = "${now()} $level/$component $message"
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > TAIL_LINES) tail.removeFirst()
        }
        pending.append(line).append('\n')
        pendingFile = activeSegment(dir)
        if (!flushScheduled) {
            flushScheduled = true
            writer.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Append buffered lines, recycling the oldest segment on capacity. Writer thread only. */
    private fun flush() {
        flushScheduled = false
        if (pending.isEmpty()) return
        val dir = pendingFile?.parentFile ?: return
        val lines = pending.toString().lineSequence().filter { it.isNotEmpty() }.toList()
        pending.setLength(0)
        runCatching {
            for (line in lines) {
                val bytes = (line + "\n").toByteArray().size
                var active = activeSegment(dir)
                if (active.length() + bytes > SEGMENT_BYTES) {
                    // The next index is the oldest segment; reset it before reuse.
                    advanceSegment(dir)
                    active = activeSegment(dir)
                    active.writeText("")
                }
                active.appendText(line + "\n")
                pendingFile = active
            }
        }
    }

    private fun segmentIndex(dir: File): Int =
        runCatching { File(dir, INDEX_FILE).readText().trim().toInt() }
            .getOrNull()?.coerceIn(0, SEGMENT_COUNT - 1) ?: 0

    private fun activeSegment(dir: File) = File(dir, "$SEGMENT_PREFIX${segmentIndex(dir)}$SEGMENT_SUFFIX")

    private fun segmentFiles(dir: File): List<File> =
        (0 until SEGMENT_COUNT).map { File(dir, "$SEGMENT_PREFIX$it$SEGMENT_SUFFIX") }

    private fun advanceSegment(dir: File) {
        File(dir, INDEX_FILE).writeText(((segmentIndex(dir) + 1) % SEGMENT_COUNT).toString())
    }

    /** Chronological order when the active index is A: A+1, A+2, ... A (mod N). */
    private fun readAllSegments(dir: File): String {
        val active = segmentIndex(dir)
        val sb = StringBuilder()
        for (offset in 1..SEGMENT_COUNT) {
            val idx = (active + offset) % SEGMENT_COUNT
            val file = File(dir, "$SEGMENT_PREFIX$idx$SEGMENT_SUFFIX")
            if (file.exists()) sb.append(file.readText())
        }
        return sb.toString()
    }

    /** One-time import of the old two-session files into segment 0. */
    private fun migrateLegacy(dir: File) {
        val old = listOf(CURRENT_FILE, LAST_FILE, LEGACY_FILE).map { File(dir, it) }.filter { it.exists() }
        if (old.isEmpty()) return
        val sb = StringBuilder()
        old.forEach { file ->
            val text = file.readText()
            sb.append(text)
            if (text.isNotEmpty() && !text.endsWith("\n")) sb.append('\n')
            file.delete()
        }
        val seg0 = File(dir, "${SEGMENT_PREFIX}0$SEGMENT_SUFFIX")
        seg0.writeText(sb.toString() + if (seg0.exists()) seg0.readText() else "")
    }
}
