package moe.n4tsu.dextop

import android.content.Context
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Assembles a sanitized, size-capped diagnostics ZIP for user-triggered export. */
internal object DiagnosticExport {
    const val MIME_ZIP = "application/zip"
    private const val MAX_ENTRY_BYTES = 512 * 1024
    private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024

    /** All sensitive sections default OFF; the UI asks for explicit consent. */
    data class Options(
        val includeSystemSettings: Boolean = false,
        val includeProcDumps: Boolean = false,
        val includeFullSessionLog: Boolean = false,
    )

    fun fileName(context: Context): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        return "dextop-diagnostics-${context.packageName}-$version-$stamp.zip"
    }

    /** Writes the ZIP to [out]; the caller owns and closes the stream. */
    fun write(context: Context, out: OutputStream, options: Options) {
        val zip = ZipOutputStream(out.buffered())
        var total = 0
        fun add(name: String, text: String) {
            val clean = OperationLog.sanitizeDocument(text)
            val bytes = clean.toByteArray()
            val limited = if (bytes.size > MAX_ENTRY_BYTES) bytes.copyOf(MAX_ENTRY_BYTES) else bytes
            if (total + limited.size > MAX_TOTAL_BYTES) return
            zip.putNextEntry(ZipEntry(name))
            zip.write(limited)
            zip.closeEntry()
            total += limited.size
        }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        add("manifest.txt", buildString {
            appendLine("DEXTOP DIAGNOSTICS EXPORT")
            appendLine("app=${context.packageName}")
            appendLine("version=${info.versionName} (${info.longVersionCode})")
            appendLine("generated=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}")
            appendLine("includeSystemSettings=${options.includeSystemSettings}")
            appendLine("includeProcDumps=${options.includeProcDumps}")
            appendLine("includeFullSessionLog=${options.includeFullSessionLog}")
        })
        add("diagnostic-report.txt", DiagnosticReport(context).build(options))
        add("session-log.txt", if (options.includeFullSessionLog)
            OperationLog.snapshot(context) else OperationLog.tailSnapshot())
        add("flutter-errors.txt", OperationLog.tailSnapshot())
        add("capability-snapshot.txt", CapabilitySnapshotEngine(context).snapshot()
            .toReportLines().joinToString("\n"))
        zip.close()
    }
}
