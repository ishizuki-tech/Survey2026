package com.negi.survey

import com.negi.survey.net.DiagnosticUploadInstrumentationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyAppDiagnosticUploadGateTest {
    @Test
    fun only_explicit_true_disables_startup_diagnostic_upload_scheduling() {
        assertFalse(DiagnosticUploadInstrumentationGate.isDisabled(null))
        assertFalse(DiagnosticUploadInstrumentationGate.isDisabled("false"))
        assertFalse(DiagnosticUploadInstrumentationGate.isDisabled("anything else"))
        assertTrue(DiagnosticUploadInstrumentationGate.isDisabled("true"))
        assertTrue(DiagnosticUploadInstrumentationGate.isDisabled(" TRUE "))
    }
}
