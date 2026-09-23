/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyExportJsonBuilderTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import com.negi.survey.BuildConfig
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveyViewModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyExportJsonBuilderTest {
    @Test
    fun build_preservesCompletedSurveyExportContract() {
        val surveyId = "1ab2b0d7-1005-4f13-a236-a9f361c72158"
        val output = SurveyExportJsonBuilder.build(
            SurveyFinalizationSnapshot(
                surveyId = surveyId,
                questions = linkedMapOf(
                    "Q1" to "What yield target do you need?",
                    "Q11" to "Which pests should the variety resist?"
                ),
                answers = linkedMapOf(
                    "Q1" to "At least twenty percent higher yield.",
                    "Q11" to "Fall armyworm and maize stalk borer."
                ),
                followups = linkedMapOf(
                    "Q11" to listOf(
                        SurveyViewModel.FollowupEntry(
                            question = "What specific pests should the variety resist?",
                            answer = "Fall armyworm and maize stalk borer."
                        )
                    )
                ),
                audioRefs = listOf(
                    SurveyViewModel.AudioRef(
                        surveyId = surveyId,
                        questionId = "Q11",
                        fileName = "voice/session/Q11.wav"
                    )
                ),
                aiOutcomesJson =
                    """{"Q11":{"score":95,"status":"ACHIEVED","reason":"Both missing points answered"}}""",
                extraMeta = linkedMapOf("session_free_text" to "Farmer grows maize.")
            ),
            exportedAt = "2026-09-22_11-37-48"
        )

        val root = Json.parseToJsonElement(output).jsonObject
        assertEquals(surveyId, root.getValue("survey_id").jsonPrimitive.content)
        assertEquals(
            BuildConfig.GIT_COMMIT_SHA,
            root.getValue("build").jsonPrimitive.content
        )
        assertEquals("2026-09-22_11-37-48", root.getValue("exported_at").jsonPrimitive.content)
        assertEquals(
            "Farmer grows maize.",
            root.getValue("meta").jsonObject.getValue("session_free_text").jsonPrimitive.content
        )

        val answers = root.getValue("answers").jsonObject
        assertEquals(
            "What yield target do you need?",
            answers.getValue("Q1").jsonObject.getValue("question").jsonPrimitive.content
        )
        assertEquals(
            "At least twenty percent higher yield.",
            answers.getValue("Q1").jsonObject.getValue("answer").jsonPrimitive.content
        )
        assertEquals(
            "Q11.wav",
            answers.getValue("Q11").jsonObject
                .getValue("audio").jsonArray[0].jsonObject.getValue("file").jsonPrimitive.content
        )

        val aiOutcomes = root.getValue("ai_outcomes").jsonObject
        assertEquals(95, aiOutcomes.getValue("Q11").jsonObject.getValue("score").jsonPrimitive.int)
        assertEquals(
            "ACHIEVED",
            aiOutcomes.getValue("Q11").jsonObject.getValue("status").jsonPrimitive.content
        )
        assertEquals(
            "Both missing points answered",
            aiOutcomes.getValue("Q11").jsonObject.getValue("reason").jsonPrimitive.content
        )

        val followup = root.getValue("followups").jsonObject.getValue("Q11").jsonArray[0].jsonObject
        assertEquals(
            "What specific pests should the variety resist?",
            followup.getValue("question").jsonPrimitive.content
        )
        assertEquals(
            "Fall armyworm and maize stalk borer.",
            followup.getValue("answer").jsonPrimitive.content
        )

        val voiceFile = root.getValue("voice_files").jsonArray[0].jsonObject
        assertEquals("Q11.wav", voiceFile.getValue("file").jsonPrimitive.content)
        assertEquals(surveyId, voiceFile.getValue("survey_id").jsonPrimitive.content)
        assertEquals("Q11", voiceFile.getValue("question_id").jsonPrimitive.content)
        assertEquals(
            "Which pests should the variety resist?",
            voiceFile.getValue("question").jsonPrimitive.content
        )
        assertEquals(
            "Fall armyworm and maize stalk borer.",
            voiceFile.getValue("answer").jsonPrimitive.content
        )
    }
}
