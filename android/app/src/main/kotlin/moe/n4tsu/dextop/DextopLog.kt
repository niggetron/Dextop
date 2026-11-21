package moe.n4tsu.dextop

import android.content.Context
import android.util.Log

/**
 * Single structured-log facade for native code and the Dart bridge.
 * Writes one sanitized line to logcat and to the privacy-filtered OperationLog.
 */
internal object DextopLog {
    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is Throwable -> value.javaClass.simpleName
        else -> value.toString()
    }

    private fun fieldsText(fields: Map<String, Any?>): String =
        fields.entries.joinToString(" ") { (key, value) -> "$key=${render(value)}" }

    private fun emit(
        context: Context?,
        level: String,
        tag: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        val suffix = buildString {
            if (fields.isNotEmpty()) append(' ').append(fieldsText(fields))
            if (error != null) append(" error=").append(error.javaClass.simpleName)
        }
        val sanitized = OperationLog.sanitizeForReport("$message$suffix")
        when (level) {
            "E" -> if (error != null) Log.e(tag, sanitized, error) else Log.e(tag, sanitized)
            "W" -> if (error != null) Log.w(tag, sanitized, error) else Log.w(tag, sanitized)
            "D" -> Log.d(tag, sanitized)
            else -> Log.i(tag, sanitized)
        }
        // Debug lines are logcat-only to bound persisted volume.
        if (context != null && level != "D") {
            OperationLog.appendFormatted(context, level, tag, sanitized)
        }
    }

    fun i(context: Context?, tag: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(context, "I", tag, message, fields, null)

    fun w(
        context: Context?, tag: String, message: String,
        fields: Map<String, Any?> = emptyMap(), error: Throwable? = null,
    ) = emit(context, "W", tag, message, fields, error)

    fun e(
        context: Context?, tag: String, message: String,
        fields: Map<String, Any?> = emptyMap(), error: Throwable? = null,
    ) = emit(context, "E", tag, message, fields, error)

    fun d(tag: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        emit(null, "D", tag, message, fields, null)

    /** Dispatcher used by the Dart `logEvent` channel. */
    fun log(
        level: String, tag: String, message: String,
        fields: Map<String, Any?>, stack: String? = null, context: Context? = null,
    ) {
        val merged = if (stack.isNullOrBlank()) fields else fields + ("stack" to stack.take(500))
        when (level.lowercase()) {
            "w" -> w(context, tag, message, merged)
            "e" -> e(context, tag, message, merged)
            else -> i(context, tag, message, merged)
        }
    }
}
