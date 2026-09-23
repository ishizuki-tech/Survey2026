package com.negi.survey.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyUploadWorkTrackerTest {

    @Test
    fun blankSurveyIdIsRejected() {
        val tracker = tracker()

        assertNull(tracker.get("  "))
        assertNull(tracker.prepare(" ", UUID.randomUUID(), SurveyUploadWorkTracker.IdentityKind.LOGICAL))
        assertFalse(tracker.markEnqueuedIfMatches("", UUID.randomUUID()))
        assertFalse(tracker.clearForUploaded(""))
    }

    @Test
    fun normalizationAndHistoricalIdsAddressTheSameRecord() {
        val tracker = tracker()
        val requestId = UUID.randomUUID()

        tracker.prepare(" Legacy Survey/Id ", requestId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)

        assertEquals(
            requestId,
            tracker.get("legacy survey/id")!!.workRequestId
        )
        assertEquals(
            tracker.storageKeyFor(" Legacy Survey/Id "),
            tracker.storageKeyFor("legacy survey/id")
        )
    }

    @Test
    fun preparePersistsPreparedLogicalAndLegacyRecords() {
        val logical = tracker()
        val legacy = tracker()
        val logicalId = UUID.randomUUID()
        val legacyId = UUID.randomUUID()

        val logicalRecord = logical.prepare("logical", logicalId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        val legacyRecord = legacy.prepare("legacy", legacyId, SurveyUploadWorkTracker.IdentityKind.LEGACY)

        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, logicalRecord!!.phase)
        assertEquals(logicalId, logicalRecord.workRequestId)
        assertEquals(SurveyUploadWorkTracker.IdentityKind.LOGICAL, logicalRecord.identityKind)
        assertEquals(SurveyUploadWorkTracker.IdentityKind.LEGACY, legacyRecord!!.identityKind)
    }

    @Test
    fun secondPrepareReplacesOnlyTheSameNormalizedSurveyRecord() {
        val tracker = tracker()
        val first = UUID.randomUUID()
        val replacement = UUID.randomUUID()
        val different = UUID.randomUUID()

        tracker.prepare("Survey-A", first, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        tracker.prepare(" survey-a ", replacement, SurveyUploadWorkTracker.IdentityKind.LEGACY)
        tracker.prepare("Survey-B", different, SurveyUploadWorkTracker.IdentityKind.LOGICAL)

        assertEquals(replacement, tracker.get("SURVEY-A")!!.workRequestId)
        assertEquals(SurveyUploadWorkTracker.IdentityKind.LEGACY, tracker.get("survey-a")!!.identityKind)
        assertEquals(different, tracker.get("survey-b")!!.workRequestId)
    }

    @Test
    fun markEnqueuedIfMatchesTransitionsOnlyMatchingPreparedRecordAndUpdatesTimestamp() {
        var now = 100L
        val tracker = tracker { now }
        val requestId = UUID.randomUUID()
        tracker.prepare("survey", requestId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        assertEquals(100L, tracker.get("survey")!!.updatedAtEpochMs)
        now = 200L

        assertTrue(tracker.markEnqueuedIfMatches(" SURVEY ", requestId))
        val record = tracker.get("survey")!!
        assertEquals(SurveyUploadWorkTracker.Phase.ENQUEUED, record.phase)
        assertEquals(200L, record.updatedAtEpochMs)
        assertFalse(tracker.markEnqueuedIfMatches("survey", requestId))
    }

    @Test
    fun mismatchedUuidCannotTransitionOrClearNewerRecord() {
        val tracker = tracker()
        val staleId = UUID.randomUUID()
        val currentId = UUID.randomUUID()
        tracker.prepare("survey", currentId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)

        assertFalse(tracker.markEnqueuedIfMatches("survey", staleId))
        assertFalse(tracker.clearIfMatches("survey", staleId))
        assertEquals(currentId, tracker.get("survey")!!.workRequestId)
    }

    @Test
    fun clearIfMatchesAndClearForUploadedRemoveCurrentRecord() {
        val tracker = tracker()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        tracker.prepare("first", first, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        tracker.prepare("second", second, SurveyUploadWorkTracker.IdentityKind.LOGICAL)

        assertTrue(tracker.clearIfMatches("first", first))
        assertNull(tracker.get("first"))
        assertTrue(tracker.clearForUploaded("second"))
        assertNull(tracker.get("second"))
    }

    @Test
    fun corruptOrMismatchedPersistedRecordsFailSafely() {
        val prefs = InMemoryPreferences()
        val tracker = tracker(prefs)
        val key = tracker.storageKeyFor("survey")!!
        prefs.putString("$key:surveyId", "other")
        prefs.putString("$key:requestId", UUID.randomUUID().toString())
        prefs.putString("$key:phase", "PREPARED")
        prefs.putString("$key:identityKind", "LOGICAL")
        prefs.putLong("$key:updatedAtEpochMs", 1L)

        assertNull(tracker.get("survey"))

        prefs.putString("$key:surveyId", "survey")
        prefs.putString("$key:requestId", "not-a-uuid")
        assertNull(tracker.get("survey"))
    }

    @Test
    fun recordSurvivesNewTrackerInstanceWithSamePreferences() {
        val prefs = InMemoryPreferences()
        val requestId = UUID.randomUUID()
        tracker(prefs).prepare("survey", requestId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)

        val restored = tracker(prefs).get("SURVEY")

        assertEquals(requestId, restored!!.workRequestId)
        assertEquals("survey", restored.normalizedSurveyId)
    }

    private fun tracker(
        prefs: InMemoryPreferences = InMemoryPreferences(),
        now: () -> Long = { 1L }
    ): SurveyUploadWorkTracker = SurveyUploadWorkTracker(prefs.sharedPreferences, now)

    private class InMemoryPreferences {
        private val values = mutableMapOf<String, Any>()

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "getLong" -> values[args[0] as String] as? Long ?: args[1]
                "edit" -> editor
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args[0] as String] = args[1] as String
                    editor
                }

                "putLong" -> {
                    values[args[0] as String] = args[1] as Long
                    editor
                }

                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }

                "commit" -> true
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        fun putString(key: String, value: String) {
            values[key] = value
        }

        fun putLong(key: String, value: Long) {
            values[key] = value
        }
    }
}
