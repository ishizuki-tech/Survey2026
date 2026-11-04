package com.negi.survey.net

import android.os.Bundle

/** Explicit instrumentation-only control for suppressing external diagnostic uploads. */
internal object DiagnosticUploadInstrumentationGate {
    const val ARG_DISABLE_DIAGNOSTIC_UPLOAD = "disableDiagnosticUpload"

    internal enum class WorkerDisposition {
        SKIP_DIAGNOSTIC,
        DEFER_SURVEY,
        REJECT_MALFORMED_SURVEY,
    }

    fun isDisabled(): Boolean = isDisabled(instrumentationArgument(ARG_DISABLE_DIAGNOSTIC_UPLOAD))

    internal fun isDisabled(argument: String?): Boolean =
        argument?.trim()?.equals("true", ignoreCase = true) == true

    /**
     * Survey JSON is product data, not diagnostics. During instrumentation suppression it must
     * remain pending so a later reconciliation (and Phase-2 concurrency inspection) can see it.
     */
    internal fun classifyWorker(uploadKind: String?, surveyId: String?): WorkerDisposition =
        if (uploadKind?.trim() != GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON) {
            WorkerDisposition.SKIP_DIAGNOSTIC
        } else if (surveyId.isNullOrBlank()) {
            WorkerDisposition.REJECT_MALFORMED_SURVEY
        } else {
            WorkerDisposition.DEFER_SURVEY
        }

    private fun instrumentationArgument(name: String): String? = runCatching {
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        val arguments = registry.getMethod("getArguments").invoke(null) as? Bundle
        arguments?.getString(name)
    }.getOrNull()
}
