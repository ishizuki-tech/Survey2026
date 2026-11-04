package com.negi.survey.issue86

import androidx.work.WorkInfo
import com.negi.survey.net.SurveyUploadWork
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class Issue86CompetingWorkTest {
    private val surveyId = "issue86-competing-work"
    private val logical = requireNotNull(SurveyUploadWork.logicalWorkName(surveyId))
    private val legacy = SurveyUploadWork.legacyWorkName(File("legacy.json"))
    private val names = listOf(logical, legacy)

    @Test fun activeLogicalWorkFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            checkNoCompetingSurveyWork(names) { if (it == logical) listOf(WorkInfo.State.ENQUEUED) else emptyList() }
        }
    }

    @Test fun activeLegacyWorkFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            checkNoCompetingSurveyWork(names) { if (it == legacy) listOf(WorkInfo.State.RUNNING) else emptyList() }
        }
    }

    @Test fun terminalWorkDoesNotBlock() {
        checkNoCompetingSurveyWork(names) { listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED) }
    }

    @Test fun queryFailurePropagates() {
        assertThrows(IllegalStateException::class.java) { checkNoCompetingSurveyWork(names) { error("query failed") } }
    }

    @Test fun unrelatedWorkIsNotQueried() {
        val unrelated = requireNotNull(SurveyUploadWork.logicalWorkName("unrelated"))
        checkNoCompetingSurveyWork(names) { if (it == unrelated) listOf(WorkInfo.State.ENQUEUED) else emptyList() }
    }

    @Test fun noWorkDoesNotBlock() {
        checkNoCompetingSurveyWork(names) { emptyList() }
    }
}
