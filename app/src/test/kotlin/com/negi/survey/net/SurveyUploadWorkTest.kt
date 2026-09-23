/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadWorkTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyUploadWorkTest {
    @Test
    fun surveyJsonMetadata_marksOnlyNonblankSurveyIdsAndKeepsActualFileIdentity() {
        val data = Data.Builder()
        SurveyUploadWork.addSurveyJsonMetadata(data, " survey-uuid ")
        val built = data.build()

        assertEquals(
            GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
            built.getString(GitHubUploadWorker.KEY_UPLOAD_KIND)
        )
        assertEquals("survey-uuid", built.getString(GitHubUploadWorker.KEY_SURVEY_ID))
        assertEquals(
            "exports/timestamp_survey_Device_survey-uuid.json",
            SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
        )
        assertEquals(
            SurveyUploadWork.uniqueWorkName(
                SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
            ),
            SurveyUploadWork.uniqueWorkName(
                SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
            )
        )
    }

    @Test
    fun surveyJsonMetadata_ignoresBlankSurveyId() {
        val data = Data.Builder()
        SurveyUploadWork.addSurveyJsonMetadata(data, "   ")
        val built = data.build()

        assertNull(built.getString(GitHubUploadWorker.KEY_UPLOAD_KIND))
        assertNull(built.getString(GitHubUploadWorker.KEY_SURVEY_ID))
    }
}
