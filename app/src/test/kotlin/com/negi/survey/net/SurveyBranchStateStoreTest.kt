package com.negi.survey.net

import com.negi.survey.utils.newTimeOrderedSurveyId
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SurveyBranchStateStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val sid = newTimeOrderedSurveyId()
    private fun state(answer: String, revision: Long, inactive: Boolean) = SurveyBranchStateStore.State(
        sid, mapOf("Q14a" to answer), mapOf("Q14b" to revision), if (inactive) setOf("Q14b") else emptySet())

    @Test fun committedChoiceRevisionAndMembershipReopenAsOneRecord() {
        val store = SurveyBranchStateStore(temp.root)
        store.commit(state("Yes", 0, false))
        store.commit(state("No", 1, true))
        assertEquals(state("No", 1, true), SurveyBranchStateStore(temp.root).read(sid))
        store.commit(state("Yes", 1, false))
        assertEquals(state("Yes", 1, false), SurveyBranchStateStore(temp.root).read(sid))
    }

    @Test fun interruptionBeforeRenameKeepsWholePreviousRecord() {
        SurveyBranchStateStore(temp.root).commit(state("Yes", 0, false))
        val failing = SurveyBranchStateStore(temp.root) { target, value ->
            File(target.parentFile, ".write-interrupted.part").writeText(value.take(20))
            throw IOException("interrupted before rename")
        }
        assertThrows(IOException::class.java) { failing.commit(state("No", 1, true)) }
        assertEquals(state("Yes", 0, false), SurveyBranchStateStore(temp.root).read(sid))
    }

    @Test fun failureAfterAtomicWriteNeverExposesMixedChoiceAndRevision() {
        SurveyBranchStateStore(temp.root).commit(state("Yes", 0, false))
        val failing = SurveyBranchStateStore(temp.root) { target, value ->
            V2ArtifactStore.writeAtomically(target, value)
            throw IOException("failure after rename")
        }
        assertThrows(IOException::class.java) { failing.commit(state("No", 1, true)) }
        assertEquals(state("No", 1, true), SurveyBranchStateStore(temp.root).read(sid))
    }

    @Test fun absentLegacyRecordExcludesBranchButDoesNotExcludeUnrelatedNodes() {
        File(temp.root, ".write-interrupted.part").writeText("incomplete first commit")
        val snapshot = SurveyBranchStateStore(temp.root).recoverySnapshot(sid)
        assertEquals(setOf("Q14b"), snapshot.excludedNodeIds)
        assertFalse("Q15" in snapshot.excludedNodeIds)
    }

    @Test fun journalWithoutBranchRevisionDoesNotAuthorizeHistoricalRecordings() {
        SurveyBranchStateStore(temp.root).commit(SurveyBranchStateStore.State(
            sid, mapOf("Q14a" to "Yes"), emptyMap(), emptySet()))
        assertEquals(setOf("Q14b"), SurveyBranchStateStore(temp.root).recoverySnapshot(sid).excludedNodeIds)
    }

    @Test fun corruptRecordBlocksRecoveryRatherThanRestoringDefaultRevision() {
        SurveyBranchStateStore(temp.root).commit(state("No", 1, true))
        File(temp.root, "$sid.json").appendText("corruption")
        assertThrows(IllegalStateException::class.java) { SurveyBranchStateStore(temp.root).recoverySnapshot(sid) }
    }
}
