package com.negi.survey.net

import android.os.Bundle

/** Explicit instrumentation-only control for suppressing external diagnostic uploads. */
internal object DiagnosticUploadInstrumentationGate {
    const val ARG_DISABLE_DIAGNOSTIC_UPLOAD = "disableDiagnosticUpload"

    fun isDisabled(): Boolean = isDisabled(instrumentationArgument(ARG_DISABLE_DIAGNOSTIC_UPLOAD))

    internal fun isDisabled(argument: String?): Boolean =
        argument?.trim()?.equals("true", ignoreCase = true) == true

    private fun instrumentationArgument(name: String): String? = runCatching {
        val registry = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        val arguments = registry.getMethod("getArguments").invoke(null) as? Bundle
        arguments?.getString(name)
    }.getOrNull()
}
