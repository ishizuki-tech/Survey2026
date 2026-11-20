package com.negi.survey.whisper

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val captures = mutableListOf<CaptureCache.Capture>()
    private fun generation() = UUID.randomUUID().toString()
    private fun owner(token: String = generation()) = CaptureCache(temp.root, token)
    private fun begin(owner: CaptureCache) = owner.begin().also { captures += it }
    private fun entry(capture: CaptureCache.Capture) = requireNotNull(capture.wav.parentFile)
    private fun loseProcess(capture: CaptureCache.Capture) { capture.close() }
    private fun validWav(file: File) {
        File("src/androidTest/assets/whisper/jfk.wav").copyTo(file, overwrite = true)
    }
    @After fun releaseLocks() { captures.forEach { it.close() } }

    @Test fun uniqueIdentityAndCommittedOwnershipPrecedePayloadUse() {
        val token = generation(); val capture = begin(owner(token))
        assertEquals(listOf("capture-cache-v1", capture.id, token), File(entry(capture), "owner").readLines())
        assertTrue(File(entry(capture), "ready").isFile)
        assertTrue(capture.wav.exists()); assertTrue(capture.pcm.exists())
        assertEquals(capture.id, entry(capture).name)
    }

    @Test fun activePcmSurvivesOtherGenerationAdmission() {
        val capture = begin(owner()); capture.pcm.writeText("captured bytes")
        begin(owner())
        assertEquals("captured bytes", capture.pcm.readText())
    }

    @Test fun activeEmptyWavSurvivesOtherGenerationAdmission() {
        val capture = begin(owner()); begin(owner())
        assertTrue(capture.wav.exists()); assertEquals(0, capture.wav.length())
    }

    @Test fun wavFinalizationIsProtectedWhileHeaderAndPayloadAreBeingWritten() {
        val capture = begin(owner()); capture.wav.writeText("RIFF partial conversion")
        begin(owner())
        assertEquals("RIFF partial conversion", capture.wav.readText())
    }

    @Test fun transcriptionReaderKeepsCompletedWavProtected() {
        val capture = begin(owner()); validWav(capture.wav); capture.pcm.delete()
        val original = capture.wav.readBytes()
        begin(owner())
        assertArrayEquals(original, capture.wav.readBytes())
    }

    @Test fun completedWavRemainsUntilLifecycleOwnerFinishes() {
        val capture = begin(owner()); validWav(capture.wav)
        assertTrue(capture.wav.length() > 44)
        capture.finish()
        assertFalse(capture.wav.exists()); assertFalse(capture.pcm.exists())
        assertFalse(entry(capture).exists())
    }

    @Test fun nextCaptureHasFreshIdentityAndPathsAfterNormalCleanup() {
        val cache = owner(); val first = begin(cache); first.finish(); val second = begin(cache)
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.wav, second.wav); assertNotEquals(first.pcm, second.pcm)
    }

    @Test fun abandonedGenerationIsReclaimedWithoutRunningItsNormalCleanup() {
        val abandoned = begin(owner()); abandoned.pcm.writeText("interrupted bytes"); loseProcess(abandoned)
        assertTrue(abandoned.pcm.exists())
        begin(owner())
        assertFalse(entry(abandoned).exists())
    }

    @Test fun ambiguousLegacyAndV2FilesStayOutsideManagedCleanup() {
        val legacyWav = File(temp.root, "whisper_rec/survey_input_legacy.wav").apply { requireNotNull(parentFile).mkdirs(); createNewFile() }
        val legacyPcm = File(temp.root, "rec_legacy.pcm").apply { writeText("legacy") }
        val v2 = File(temp.root, "v2_artifacts/voice/payload.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("sealed evidence") }
        val metadata = File(v2.parentFile, "voice.meta.json").apply { writeText("recording identity") }
        val abandoned = begin(owner()); loseProcess(abandoned); begin(owner())
        assertTrue(legacyWav.exists()); assertEquals("legacy", legacyPcm.readText())
        assertEquals("sealed evidence", v2.readText()); assertEquals("recording identity", metadata.readText())
    }

    @Test fun repeatedCleanupIsIdempotentAndDoesNotCreatePublicationFiles() {
        val old = begin(owner()); loseProcess(old)
        val current = owner()
        repeat(3) { begin(current).finish() }
        assertFalse(entry(old).exists())
        assertFalse(temp.root.walkTopDown().any { it.name in setOf("voice.outcome", "voice.meta.json", "route") })
    }

    @Test fun releasedCurrentGenerationIsStillPreservedByOrphanSweep() {
        val token = generation(); val abandoned = begin(owner(token)); loseProcess(abandoned)
        begin(owner(token))
        assertTrue(abandoned.wav.exists()); assertTrue(abandoned.pcm.exists())
    }

    @Test fun missingOwnerMetadataFailsClosed() {
        val capture = begin(owner()); loseProcess(capture); File(entry(capture), "owner").delete()
        begin(owner()); assertTrue(capture.pcm.exists()); assertTrue(capture.wav.exists())
    }

    @Test fun invalidOwnerMetadataFailsClosed() {
        val capture = begin(owner()); loseProcess(capture); File(entry(capture), "owner").writeText("partial owner")
        begin(owner()); assertTrue(capture.wav.exists())
    }

    @Test fun partiallyCreatedOwnerRecordFailsClosed() {
        val id = generation(); val directory = File(temp.root, "capture-cache-v1/$id").apply { mkdirs() }
        File(directory, "owner.pending").writeText("capture-cache-v1\n$id")
        val pcm = File(directory, "capture.pcm").apply { writeText("retain partial initialization") }
        begin(owner()); assertEquals("retain partial initialization", pcm.readText())
    }

    @Test fun uncommittedPayloadInitializationFailsClosed() {
        val capture = begin(owner()); loseProcess(capture); File(entry(capture), "ready").delete()
        begin(owner()); assertTrue(capture.wav.exists()); assertTrue(capture.pcm.exists())
    }

    @Test fun staleOwnerRecordCannotClaimDifferentCapture() {
        val first = begin(owner()); val second = begin(owner())
        File(entry(first), "owner").copyTo(File(entry(second), "owner"), overwrite = true)
        loseProcess(first); loseProcess(second); begin(owner())
        assertTrue(second.wav.exists()); assertTrue(second.pcm.exists())
    }

    @Test fun unknownFilesAndMetadataArePreserved() {
        val capture = begin(owner()); loseProcess(capture)
        File(entry(capture), "voice.outcome").writeText("retain unknown evidence")
        begin(owner()); assertTrue(capture.wav.exists())
        assertEquals("retain unknown evidence", File(entry(capture), "voice.outcome").readText())
    }

    @Test fun normalReleasePreservesFilesWhenOwnershipMetadataWasChanged() {
        val capture = begin(owner())
        File(entry(capture), "owner").writeText("ambiguous metadata")
        capture.finish()
        assertTrue(capture.pcm.exists()); assertTrue(capture.wav.exists())
    }

    @Test fun normalReleasePreservesUnknownEvidenceInsideEntry() {
        val capture = begin(owner())
        File(entry(capture), "unknown").writeText("preserve")
        capture.finish()
        assertTrue(capture.pcm.exists()); assertTrue(capture.wav.exists())
    }

    @Test fun completedPriorGenerationWavAndItsPcmArePreserved() {
        val capture = begin(owner()); validWav(capture.wav); capture.pcm.writeText("matching raw evidence")
        val bytes = capture.wav.readBytes(); loseProcess(capture); begin(owner())
        assertArrayEquals(bytes, capture.wav.readBytes()); assertEquals("matching raw evidence", capture.pcm.readText())
    }

    @Test fun symlinkPayloadAndTargetAreNeverDeleted() {
        val capture = begin(owner()); loseProcess(capture); capture.pcm.delete()
        val outside = File(temp.root, "protected.pcm").apply { writeText("outside namespace") }
        Files.createSymbolicLink(capture.pcm.toPath(), outside.toPath())
        begin(owner()); assertTrue(capture.wav.exists()); assertEquals("outside namespace", outside.readText())
    }

    @Test fun symlinkNamespaceIsRejectedWithoutTouchingTarget() {
        val outside = temp.newFolder("outside"); val marker = File(outside, "marker").apply { writeText("keep") }
        Files.createSymbolicLink(File(temp.root, "capture-cache-v1").toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { owner().begin() }
        assertEquals("keep", marker.readText())
    }

    @Test fun deletionFailureIsDiagnosedAndDoesNotPreventNewCapture() {
        val old = begin(owner()); loseProcess(old); val diagnostics = mutableListOf<String>()
        val current = CaptureCache(temp.root, generation(), delete = { false }, diagnostic = diagnostics::add)
        val fresh = begin(current)
        assertTrue(old.pcm.exists()); assertTrue(fresh.pcm.exists())
        assertTrue(diagnostics.contains("Capture cache deletion deferred"))
    }

    @Test fun deletionExceptionDoesNotAlterCompletedEvidence() {
        val old = begin(owner()); loseProcess(old); val messages = mutableListOf<String>()
        begin(CaptureCache(temp.root, generation(), delete = { throw java.io.IOException("disk failure") }, diagnostic = messages::add))
        assertTrue(old.wav.exists()); assertTrue(messages.isNotEmpty())
    }

    @Test fun closingOwnerStaysProtectedUntilCloseCompletes() {
        val capture = begin(owner()); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val closing = pool.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); capture.finish() }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); begin(owner())
            assertTrue(capture.wav.exists()); assertTrue(capture.pcm.exists())
            release.countDown(); closing.get(5, TimeUnit.SECONDS)
            assertFalse(entry(capture).exists())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun simultaneousAdmissionsHaveDistinctProtectedOwners() {
        val cache = owner(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<CaptureCache.Capture> { check(start.await(5, TimeUnit.SECONDS)); cache.begin() } }
            start.countDown(); val admitted = futures.map { it.get(5, TimeUnit.SECONDS) }; captures += admitted
            begin(owner())
            assertEquals(2, admitted.map { it.id }.toSet().size)
            assertTrue(admitted.all { it.wav.exists() && it.pcm.exists() })
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun sweepCannotDeleteAnotherAdmissionBetweenCheckAndDelete() {
        val old = begin(owner()); loseProcess(old)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        val cleaner = CaptureCache(temp.root, generation(), delete = { path: Path ->
            if (path.fileName.toString() == "capture.pcm") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Files.deleteIfExists(path)
        })
        try {
            val sweep = pool.submit<CaptureCache.Capture> { cleaner.begin() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val fresh = begin(owner()); fresh.pcm.writeText("new active bytes")
            release.countDown(); captures += sweep.get(5, TimeUnit.SECONDS)
            assertEquals("new active bytes", fresh.pcm.readText()); assertTrue(fresh.wav.exists())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun actualProcessExitReleasesLockWithoutExecutingArtifactCleanup() {
        val classpath = listOf(CaptureCache::class.java, CaptureCacheProcessProbe::class.java, kotlin.Unit::class.java)
            .map { File(requireNotNull(it.protectionDomain).codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, CaptureCacheProcessProbe::class.java.name, temp.root.absolutePath)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val reader = Executors.newSingleThreadExecutor()
        try {
            val line = reader.submit<String> { process.inputStream.bufferedReader().readLine() }.get(10, TimeUnit.SECONDS)
            assertTrue(line.startsWith("READY:"))
            val directory = File(temp.root, "capture-cache-v1/" + line.removePrefix("READY:"))
            val pcm = File(directory, "capture.pcm")
            begin(owner()).finish()
            assertTrue("Another live process must keep ownership", pcm.exists())
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertTrue("No normal cleanup was run", pcm.exists())
            begin(owner()).finish()
            assertFalse("Exited process's managed entry should be reclaimed", directory.exists())
        } finally {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); reader.shutdownNow()
        }
    }

    @Test fun perAdmissionWorkIsBoundedAndCursorEventuallyReclaimsBacklog() {
        val previous = owner(); val old = (1..80).map { begin(previous).also { loseProcess(it) } }
        val next = owner(); begin(next).finish()
        val removed = old.count { !entry(it).exists() }
        assertTrue(removed in 1..CaptureCache.MAX_ENTRIES_PER_ADMISSION)
        repeat(8) { begin(next).finish() }
        assertTrue(old.all { !entry(it).exists() })
    }
}

/** Separate JVM: the parent kills this process without invoking lease cleanup or close. */
internal object CaptureCacheProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val capture = CaptureCache(File(args.single())).begin()
        capture.pcm.writeText("synthetic interrupted capture")
        println("READY:" + capture.id)
        System.out.flush()
        System.`in`.read()
    }
}
