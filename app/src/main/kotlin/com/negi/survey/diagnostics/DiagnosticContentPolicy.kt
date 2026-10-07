package com.negi.survey.diagnostics

import com.negi.survey.BuildConfig
import android.util.Log

/**
 * The single diagnostic-content policy boundary.
 *
 * This intentionally has no relationship to build type, CI, upload routing,
 * signing, or credential embedding. Those concepts do not identify a real
 * Production diagnostic policy.
 */
object DiagnosticContentPolicy {
    val permitsRawRespondentContent: Boolean
        get() = !BuildConfig.DIAGNOSTICS_PRODUCTION

    /** Returns raw content only in builds explicitly permitted to retain it. */
    fun rawOrNull(value: String?): String? =
        if (permitsRawRespondentContent) value else null

    /** Uses the exception class in Production because exception messages may echo input. */
    fun errorDescription(throwable: Throwable): String =
        if (permitsRawRespondentContent) {
            throwable.message ?: throwable.javaClass.simpleName
        } else {
            throwable.javaClass.simpleName
        }

    /** Direct logcat helper for known diagnostic error paths. */
    fun logWarning(tag: String, operation: String, throwable: Throwable) {
        if (permitsRawRespondentContent) {
            Log.w(tag, operation, throwable)
        } else {
            Log.w(tag, directLogMessage(operation, throwable, production = true))
        }
    }

    /** Direct logcat helper for known diagnostic error paths. */
    fun logError(tag: String, operation: String, throwable: Throwable) {
        if (permitsRawRespondentContent) {
            Log.e(tag, operation, throwable)
        } else {
            Log.e(tag, directLogMessage(operation, throwable, production = true))
        }
    }

    internal fun directLogMessage(
        operation: String,
        throwable: Throwable,
        production: Boolean,
    ): String =
        if (production) {
            "$operation errorClass=${throwable.javaClass.name}"
        } else {
            "$operation: ${throwable.message}"
        }

    /**
     * Last defense for persistent traces. Callers must still classify known
     * payloads explicitly; this small list protects established trace schemas
     * from an accidental raw-field regression.
     */
    fun sanitizeTraceFields(fields: Map<String, Any?>): Map<String, Any?> =
        sanitizeTraceFields(fields, production = !permitsRawRespondentContent)

    internal fun sanitizeTraceFields(
        fields: Map<String, Any?>,
        production: Boolean,
    ): Map<String, Any?> {
        if (!production) return fields
        return fields.filterKeys { it !in TRACE_RAW_CONTENT_FIELDS }
    }

    /** Keeps throwable identity and frames without persisting its message text. */
    fun throwableDetails(throwable: Throwable): String =
        if (permitsRawRespondentContent) {
            android.util.Log.getStackTraceString(throwable)
        } else {
            buildString {
                append(throwable.javaClass.name)
                throwable.stackTrace.forEach { frame -> append("\n\tat ").append(frame) }
            }
        }

    private val TRACE_RAW_CONTENT_FIELDS = setOf(
        "transcript",
        "prompt",
        "finalPrompt",
        "rawResponse",
        "candidate",
        "followup",
        "extractedFollowups",
        "error",
    )
}
