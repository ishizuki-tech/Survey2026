package com.negi.survey.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.negi.survey.vm.DeferredLegacyRecoveryEntry
import com.negi.survey.vm.LegacyRecoveryLocalStatus
import com.negi.survey.vm.UploadStatus
import com.negi.survey.vm.UploadStatusDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UploadStatusLegacyRecoveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deferredRecoveryIsVisibleOnlyWhenPresentAndClearListDoesNotRemoveIt() {
        var resolveCalls = 0
        var clearCalls = 0
        val details = UploadStatusDetails(
            pending = emptyList(),
            completed = emptyList(),
            deferredLegacyRecovery = listOf(
                DeferredLegacyRecoveryEntry(
                    surveyId = "legacy-survey-id-with-a-long-opaque-suffix",
                    fileName = "long_legacy_survey_export.json",
                    reason = "Legacy pending survey has no durable route reservation or active work.",
                    localStatus = LegacyRecoveryLocalStatus.MANUAL_REVIEW_REQUIRED,
                    operatorReviewRequired = true,
                ),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                UploadStatusSummary(
                    uploadStatus = UploadStatus(pendingCount = 1),
                    onResolveUploadStatusDetails = {
                        resolveCalls += 1
                        details
                    },
                    onClearCompletedSurveys = { clearCalls += 1 },
                )
            }
        }

        composeRule.onAllNodesWithText("Deferred upload recovery (1)").assertCountEquals(0)
        composeRule.onNodeWithTag("UploadStatusArea").performClick()
        composeRule.onNodeWithText("Deferred upload recovery (1)").assertIsDisplayed()
        composeRule.onNodeWithText("legacy-survey-id-with-a-long-opaque-suffix").assertIsDisplayed()
        composeRule.onNodeWithText("long_legacy_survey_export.json").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh local status").performClick()
        composeRule.runOnIdle { assertEquals(2, resolveCalls) }

        composeRule.onNodeWithTag("ClearCompletedUploadsButton").performClick()
        composeRule.onNodeWithText("Deferred upload recovery (1)").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, clearCalls) }
    }
}
