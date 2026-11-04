package com.negi.survey.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticUploadInstrumentationGateTest {
    @Test fun surveyJsonWithLogicalIdIsDeferredNotClassifiedAsDiagnostic() {
        assertEquals(
            DiagnosticUploadInstrumentationGate.WorkerDisposition.DEFER_SURVEY,
            DiagnosticUploadInstrumentationGate.classifyWorker(
                GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON, "survey-123"
            )
        )
    }

    @Test fun malformedSurveyMarkerFailsClosed() {
        assertEquals(
            DiagnosticUploadInstrumentationGate.WorkerDisposition.REJECT_MALFORMED_SURVEY,
            DiagnosticUploadInstrumentationGate.classifyWorker(GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON, null)
        )
    }

    @Test fun diagnosticUploadRemainsSkippable() {
        assertEquals(
            DiagnosticUploadInstrumentationGate.WorkerDisposition.SKIP_DIAGNOSTIC,
            DiagnosticUploadInstrumentationGate.classifyWorker("runtime_logs", null)
        )
    }
}
