package com.negi.survey.whisper

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises actual Android filesystem/lock semantics only inside the isolated validation app. */
class CaptureCacheInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captures = mutableListOf<CaptureCache.Capture>()
    private lateinit var directory: File

    @Before fun setup() {
        org.junit.Assume.assumeTrue(instrumentation.targetContext.packageName == "com.negi.survey.local.issue124i")
        directory = File(instrumentation.targetContext.cacheDir, "issue124i-helper-${UUID.randomUUID()}")
        check(directory.mkdir())
    }
    @After fun cleanup() {
        captures.forEach { it.close() }
        if (::directory.isInitialized) directory.deleteRecursively()
    }
    private fun cache() = CaptureCache(directory, UUID.randomUUID().toString())
    private fun begin(owner: CaptureCache) = owner.begin().also { captures += it }

    @Test fun priorGenerationReclaimedWhileLegacyAndV2EvidenceRemainUntouched() {
        val legacy = File(directory, "rec_legacy.pcm").apply { writeText("synthetic legacy") }
        val legacyWav = File(directory, "whisper_rec/survey_input_legacy.wav").apply { parentFile!!.mkdir(); createNewFile() }
        val v2 = File(directory, "v2_artifacts/voice/voice.meta.json").apply { parentFile!!.mkdirs(); writeText("identity") }
        val old = begin(cache()); old.pcm.writeText("synthetic abandoned bytes"); old.close()
        val fresh = begin(cache())
        assertFalse(old.wav.exists()); assertFalse(old.pcm.exists())
        assertTrue(fresh.wav.exists()); assertTrue(fresh.pcm.exists())
        assertEquals("synthetic legacy", legacy.readText()); assertTrue(legacyWav.exists())
        assertEquals("identity", v2.readText())
    }

    @Test fun liveCaptureAndClosingLeaseAreProtectedByAndroidFileLock() {
        val current = begin(cache()); current.pcm.writeText("synthetic active bytes")
        repeat(3) { begin(cache()).finish() }
        assertTrue(current.wav.exists()); assertEquals("synthetic active bytes", current.pcm.readText())
        current.finish()
        assertFalse(current.wav.parentFile!!.exists())
    }

    @Test fun completedWavAndPartialOwnershipArePreserved() {
        val previous = cache(); val completed = begin(previous)
        instrumentation.context.assets.open("whisper/jfk.wav").use { input -> completed.wav.outputStream().use { input.copyTo(it) } }
        val bytes = completed.wav.readBytes(); completed.close()
        val partial = begin(previous); partial.close(); File(partial.wav.parentFile, "ready").delete()
        begin(cache())
        assertArrayEquals(bytes, completed.wav.readBytes())
        assertTrue(partial.wav.exists()); assertTrue(partial.pcm.exists())
    }

    @Test fun repeatedCaptureOwnershipUsesFreshPathsAndNormalReleaseIsIdempotent() {
        val current = cache(); val ids = mutableSetOf<String>()
        repeat(4) {
            val capture = begin(current); assertTrue(ids.add(capture.id))
            assertTrue(capture.wav.canonicalPath.startsWith(File(directory, "capture-cache-v1").canonicalPath + "/"))
            capture.finish(); capture.finish()
            assertFalse(capture.wav.exists()); assertFalse(capture.pcm.exists())
        }
    }
}
