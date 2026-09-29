package com.negi.survey.diagnostics

import android.content.Context
import android.os.Process
import com.negi.survey.utils.DeviceUploadTagProvider
import java.util.UUID

/** Values shared by all records in one survey diagnostic stream. */
data class TraceIdentity(
    val surveyId: String,
    val shortSurveyId: String = surveyId.take(8),
    val deviceTag: String,
    val processId: Int,
    val appVersion: String?
) {
    companion object {
        fun forSurvey(context: Context, surveyId: String): TraceIdentity = TraceIdentity(
            surveyId = surveyId,
            deviceTag = DeviceUploadTagProvider.from(context).value,
            processId = Process.myPid(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        )

        fun newAnswerTraceId(): String = UUID.randomUUID().toString()
        fun newEventId(): String = UUID.randomUUID().toString()
    }
}

/** Pure active-transaction holder: explicit starts supersede; commits only observe. */
internal class AnswerTraceTransactions {
    private val active = mutableMapOf<String, String>()
    fun begin(questionId: String): String = TraceIdentity.newAnswerTraceId().also { active[questionId] = it }
    fun current(questionId: String): String? = active[questionId]
    fun commit(questionId: String): String? = active[questionId]
    fun clear() = active.clear()
}
