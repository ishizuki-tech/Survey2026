package com.negi.survey.net

import android.app.ApplicationExitInfo
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.negi.survey.CrashCapture
import com.negi.survey.diagnostics.SurveyTraceRegistry
import com.negi.survey.diagnostics.SurveyDiagnosticAttribution
import com.negi.survey.diagnostics.V2DiagnosticLogs
import com.negi.survey.diagnostics.V2TraceArtifacts
import com.negi.survey.diagnostics.V2SlmTrace
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.RepositoryTraceEvent
import com.negi.survey.diagnostics.WhisperTraceRegistry
import com.negi.survey.net.V2ArtifactStore.Kind
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.ExportUtils
import com.negi.survey.utils.newTimeOrderedSurveyId
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.RecordingPrompt
import com.negi.survey.vm.HOME_FREE_TEXT_QID
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class V2ExportIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = V2ArtifactStore(context)
    private val profile = V2ArtifactStore.profile
    private val stamp = "2026-11-09_00-01-00"

    @Test fun finalizerKeepsExactWavReferencesAndIndependentDelivery() = runBlocking {
        val surveyId = newTimeOrderedSurveyId(Instant.parse("2026-11-08T23:59:59.999Z").toEpochMilli())
        val source = fixtureWav()
        try {
            val question = "Swali la é 漢字"
            val prompt = RecordingPrompt(surveyId, question, RecordingPrompt.Kind.ORIGINAL, "Original?")
            val voices = (1..3).map { index ->
                val identity = RecordingIdentity(V2ArtifactStore.newId(), prompt)
                ExportUtils.exportRecordedVoice(context, source, identity).also {
                    SurveyRecordings(store).persistOutcome(it, identity, TranscriptionOutcome.from(Result.success("raw $index")))
                }
            }
            val first = voices.first().payload
            val second = voices[1].payload
            val cfg = GitHubUploader.GitHubConfig(V2ArtifactStore.OWNER, profile.repo, "synthetic-only", "main", "")
            val candidate = voices.first()
            V2ArtifactDelivery.recoverIndependent(context, cfg)
            assertFalse(V2ArtifactDelivery.isEligible(context, candidate))
            assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(V2ArtifactDelivery.workName(candidate))
                .get(5, TimeUnit.SECONDS).isEmpty())
            val early = runCatching {
                V2ArtifactDelivery.deliver(context, candidate, cfg, transport = { _, _, _ ->
                    fail("Unfinalized WAV reached transport")
                }) {}
            }
            assertTrue(early.exceptionOrNull() is IllegalStateException)
            assertNotEquals(first.name, second.name)
            assertArrayEquals(source.readBytes(), first.readBytes())
            val snapshot = snapshot(surveyId, questionId = question)
            val finalizer = SurveyUploadFinalizer(context)
            val tag = DeviceUploadTag("ignored_after_first_artifact")
            val result = finalizer.finalize(snapshot, null, tag, stamp) as SurveyFinalizationResult.StagedPending
            val originalBytes = result.file.readBytes()
            val repeated = finalizer.finalize(snapshot.copy(answers = mapOf("Q8" to "changed")), null, tag, "changed")
            assertTrue((repeated as SurveyFinalizationResult.StagedPending).reused)
            assertArrayEquals(originalBytes, repeated.file.readBytes())

            val root = Json.parseToJsonElement(result.file.readText()).jsonObject
            assertEquals(stamp, root.getValue("exported_at").jsonPrimitive.content)
            val nested = root.getValue("answers").jsonObject.getValue(question).jsonObject.getValue("audio").jsonArray
            val top = root.getValue("voice_files").jsonArray
            val files = voices.map { it.route.basename }
            assertEquals(files.sorted(), nested.map { it.jsonObject.getValue("file").jsonPrimitive.content }.sorted())
            assertEquals(files.sorted(), top.map { it.jsonObject.getValue("file").jsonPrimitive.content }.sorted())
            assertEquals(3, top.map { it.jsonObject.getValue("recording_id").jsonPrimitive.content }.toSet().size)
            val byFile = top.associateBy { it.jsonObject.getValue("file").jsonPrimitive.content }
            files.forEachIndexed { index, file ->
                assertEquals("raw ${index + 1}", byFile.getValue(file).jsonObject.getValue("transcript").jsonPrimitive.content)
            }

            val json = checkNotNull(store.read(V2ArtifactStore.surveyKey(surveyId)))
            assertEquals(question, top.first().jsonObject.getValue("question_id").jsonPrimitive.content)
            assertTrue(voices.all { V2ArtifactDelivery.isEligible(context, it) })
            assertTrue(voices.all { it.route.path.startsWith("2026-11-08/voice/") })
            assertEquals(json.route.basename.substringBefore("_survey_"), first.name.substringBefore("_voice_"))
            val remote = mutableMapOf<String, ByteArray>()
            var creates = 0
            suspend fun fakeTransport(
                entry: V2ArtifactStore.Entry,
                config: GitHubUploader.GitHubConfig,
                progress: (Int) -> Unit,
            ) {
                assertTrue(entry.route.profile.matches(config))
                val old = remote[entry.route.path]
                if (old == null) {
                    remote[entry.route.path] = entry.payload.readBytes()
                    creates++
                } else assertArrayEquals(old, entry.payload.readBytes())
                progress(100)
            }

            val ambiguous = runCatching {
                V2ArtifactDelivery.deliver(context, json, cfg, transport = { entry, config, progress ->
                    fakeTransport(entry, config, progress)
                    throw IOException("simulated loss after remote create")
                }) {}
            }.exceptionOrNull()
            assertTrue(ambiguous is IOException)
            assertTrue(json.payload.exists())
            assertFalse(V2ArtifactDelivery.confirmed(context, json))
            assertEquals(SurveyUploadExecutionAuthority.Owner.VALID(SurveyUploadExecutionAuthority.Protocol.V2),
                SurveyUploadExecutionAuthority(context).ownerFor(surveyId))

            val reopened = checkNotNull(V2ArtifactStore(context).read(json.route.id))
            V2ArtifactDelivery.deliver(context, reopened, cfg, transport = ::fakeTransport) {}
            assertEquals(1, creates)
            assertTrue(V2ArtifactDelivery.confirmed(context, reopened))
            assertFalse(reopened.payload.exists())
            assertTrue(voices.all { it.matchesPayload() && !it.hasReceipt() })
            assertTrue(voices.all { V2ArtifactDelivery.isEligible(context, checkNotNull(V2ArtifactStore(context).read(it.route.id))) })
            V2ArtifactDelivery.recoverIndependent(context, cfg)
            val manager = WorkManager.getInstance(context)
            voices.forEach {
                assertTrue(manager.getWorkInfosForUniqueWork(V2ArtifactDelivery.workName(it))
                    .get(5, TimeUnit.SECONDS).isNotEmpty())
            }
            assertEquals(SurveyUploadExecutionAuthority.Owner.NONE, SurveyUploadExecutionAuthority(context).ownerFor(surveyId))
            val resubmitted = SurveyUploadWork.reconcile(context, cfg, surveyId, reopened.payload, v2Entry = reopened)
            assertEquals(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED, resubmitted.decision.action)
            assertFalse(resubmitted.enqueued)

            V2ArtifactDelivery.deliver(context, voices.first(), cfg, transport = ::fakeTransport) {}
            assertTrue(voices.first().hasReceipt())
            assertFalse(voices.first().payload.exists())
            val held = voices.last()
            assertTrue(runCatching {
                V2ArtifactDelivery.deliver(context, held, cfg, transport = { _, _, _ ->
                    throw IOException("simulated offline WAV")
                }) {}
            }.exceptionOrNull() is IOException)
            assertTrue(held.matchesPayload())
            assertFalse(held.hasReceipt())
            assertTrue(V2ArtifactDelivery.confirmed(context, reopened))
            assertEquals(2, creates)
            V2ArtifactDelivery.deliver(context, held, cfg, transport = ::fakeTransport) {}
            assertTrue(held.hasReceipt())
            assertFalse(held.payload.exists())
            assertTrue(voices[1].matchesPayload())
            assertFalse(voices[1].hasReceipt())
        } finally {
            source.delete()
        }
    }

    @Test fun finalizationFailsExplicitlyAndCanRetryAfterInvalidMetadataIsRepaired() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        val source = fixtureWav()
        val identity = RecordingIdentity(V2ArtifactStore.newId(),
            RecordingPrompt(surveyId, "Q8", RecordingPrompt.Kind.ORIGINAL, "Original?"))
        val voice = try {
            ExportUtils.exportRecordedVoice(context, source, identity)
        } finally {
            source.delete()
        }
        val sidecar = File(voice.directory, V2ArtifactStore.VOICE_METADATA)
        val original = sidecar.readText()
        sidecar.appendText("broken")
        val finalizer = SurveyUploadFinalizer(context)
        val result = finalizer.finalize(snapshot(surveyId), null, DeviceUploadTag("test"), stamp)
        assertTrue(result is SurveyFinalizationResult.Failure && !result.staged)
        assertNull(store.read(V2ArtifactStore.surveyKey(surveyId)))
        assertTrue(voice.matchesPayload())
        sidecar.writeText(original)
        assertTrue(finalizer.finalize(snapshot(surveyId), null, DeviceUploadTag("test"), stamp)
            is SurveyFinalizationResult.StagedPending)
    }

    @Test fun recordingsRestart_writeLiveWavWithoutSurveyJson() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        val source = fixtureWav()
        val identity = RecordingIdentity(V2ArtifactStore.newId(),
            RecordingPrompt(surveyId, HOME_FREE_TEXT_QID, RecordingPrompt.Kind.SURVEY_NOTE, "Add general notes."))
        val voice = try {
            ExportUtils.exportRecordedVoice(context, source, identity)
        } finally {
            source.delete()
        }
        SurveyUploadFinalizer(context).recoverAbandonedRecordings(context)
        assertNull(store.read(V2ArtifactStore.surveyKey(surveyId)))
        File(context.noBackupFilesDir, "issue138-restart").writeText(
            "${Process.myPid()}\n$surveyId\n${voice.route.id}\n${voice.route.fingerprint}",
        )
        if (InstrumentationRegistry.getArguments().getString("holdForForceStop") == "true") {
            awaitCancellation()
        }
    }

    @Test fun recordingsRestart_readIncompleteSurveyCreatedByStartup() = runBlocking {
        val (pid, surveyId, recordingId, fingerprint) = File(context.noBackupFilesDir, "issue138-restart").readLines()
        assertNotEquals(pid.toInt(), Process.myPid())
        val survey = withTimeout(10_000) {
            var entry = store.read(V2ArtifactStore.surveyKey(surveyId))
            while (entry == null) {
                delay(25)
                entry = store.read(V2ArtifactStore.surveyKey(surveyId))
            }
            entry
        }
        val root = Json.parseToJsonElement(survey.payload.readText()).jsonObject
        val voice = checkNotNull(store.read(recordingId))
        val ref = root.getValue("voice_files").jsonArray.single().jsonObject
        assertEquals("incomplete", root.getValue("status").jsonPrimitive.content)
        assertEquals("survey_note", ref.getValue("prompt_kind").jsonPrimitive.content)
        assertEquals("Add general notes.", ref.getValue("prompt_text").jsonPrimitive.content)
        assertEquals("interrupted", ref.getValue("transcription_status").jsonPrimitive.content)
        assertNull(ref["transcript"])
        assertEquals(recordingId, ref.getValue("recording_id").jsonPrimitive.content)
        assertEquals(fingerprint, voice.route.fingerprint)
        assertTrue(voice.matchesPayload())
        assertTrue(V2ArtifactDelivery.isEligible(context, voice))
        SurveyUploadFinalizer(context).recoverAbandonedRecordings(context)
        assertEquals(survey.routeFingerprint, store.read(survey.route.id)?.routeFingerprint)
    }

    private fun fixtureWav(): File = File.createTempFile("issue138-", ".wav", context.cacheDir).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("whisper/jfk.wav").use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun workerRejectsLegacyAndInvalidRouteEvidence() = runBlocking {
        val legacy = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(workDataOf(GitHubUploadWorker.KEY_MODE to "file")).build()
        assertTrue(legacy.doWork() is ListenableWorker.Result.Failure)
        val invalid = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(workDataOf(GitHubUploadWorker.KEY_MODE to V2ArtifactDelivery.MODE,
                V2ArtifactDelivery.KEY_ARTIFACT to "missing-route")).build()
        assertTrue(invalid.doWork() is ListenableWorker.Result.Failure)
    }

    @Test fun surveyReconciliationKeepsOneRealWorkManagerChain() {
        check(DiagnosticUploadInstrumentationGate.isDisabled())
        val surveyId = newTimeOrderedSurveyId()
        val paths = store.paths(context, surveyId)
        val entry = store.stage(
            V2ArtifactStore.surveyKey(surveyId), Kind.SURVEY, surveyId, profile, paths.surveyJson(),
        ) { it.write(SurveyExportJsonBuilder.build(snapshot(surveyId), stamp).toByteArray()) }
        val cfg = GitHubUploader.GitHubConfig(V2ArtifactStore.OWNER, profile.repo, "synthetic-only", "main", "")
        val manager = WorkManager.getInstance(context)
        val work = V2ArtifactDelivery.workName(entry)
        try {
            val result = SurveyUploadWork.reconcile(context, cfg, surveyId, entry.payload, v2Entry = entry)
            assertTrue(result.enqueued)
            val first = manager.getWorkInfosForUniqueWork(work).get(5, TimeUnit.SECONDS).single()
            val repeated = SurveyUploadWork.reconcile(context, cfg, surveyId, entry.payload, v2Entry = entry)
            assertEquals(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED, repeated.decision.action)
            assertEquals(first.id, manager.getWorkInfosForUniqueWork(work).get(5, TimeUnit.SECONDS).single().id)
            assertTrue(entry.matchesPayload())
        } finally {
            manager.cancelUniqueWork(work).result.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun diagnosticsRecoverAndDeliverWithSurveyAndStandaloneAttribution() = runBlocking {
        check(DiagnosticUploadInstrumentationGate.isDisabled())
        val surveyId = newTimeOrderedSurveyId()
        val entries = mutableListOf<V2ArtifactStore.Entry>()
        val manager = WorkManager.getInstance(context)
        val cfg = GitHubUploader.GitHubConfig(V2ArtifactStore.OWNER, profile.repo, "synthetic-only", "main", "")
        SurveyDiagnosticAttribution.begin(context, surveyId)
        try {
            val event = Instant.now()
            val evidence = checkNotNull(SurveyDiagnosticAttribution.currentProcessEvidence(event, event))
            assertEquals(surveyId, evidence.surveyId)
            for (kind in listOf(Kind.LOGCAT, Kind.RUNTIME, Kind.CRASH)) {
                for (attribution in listOf(evidence, null)) {
                    val id = V2ArtifactStore.newId()
                    V2DiagnosticLogs.stage(context, kind, id, event, event, event, "synthetic", attribution) {
                        it.write("synthetic record".toByteArray())
                    }
                    val entry = checkNotNull(store.read(id))
                    assertEquals(attribution?.surveyId, entry.route.surveyId)
                    assertTrue(entry.route.basename.contains(attribution?.surveyId ?: "_no-survey_"))
                    entries += entry
                }
            }
            for (kind in listOf(Kind.SURVEY_TRACE, Kind.WHISPER_TRACE, Kind.SLM)) {
                val writer = if (kind == Kind.SLM) {
                    V2TraceArtifacts.writer(context, surveyId, kind, "Q8", "eval")
                } else V2TraceArtifacts.writer(context, surveyId, kind)
                writer.append("""{"event":"synthetic"}""")
                val payload = checkNotNull(writer.finalizeToGzip())
                entries += checkNotNull(store.read(checkNotNull(payload.parentFile).name))
            }

            assertEquals(9, entries.map { it.route.path }.toSet().size)
            assertEquals(6, entries.map { it.route.kind }.toSet().size)
            assertTrue(entries.all { it.matchesPayload() && !it.hasReceipt() })
            assertFalse(entries.single { it.route.kind == Kind.SLM }.route.diagnosticsRedacted)
            V2ArtifactDelivery.recoverIndependent(context, cfg)
            val remote = mutableMapOf<String, ByteArray>()
            for (entry in entries) {
                val work = V2ArtifactDelivery.workName(entry)
                val initial = manager.getWorkInfosForUniqueWork(work).get(5, TimeUnit.SECONDS).single()
                val bytes = entry.payload.readBytes()
                val failed = runCatching {
                    V2ArtifactDelivery.deliver(context, entry, cfg, transport = { artifact, _, _ ->
                        assertNull(remote.put(artifact.route.path, artifact.payload.readBytes()))
                        throw IOException("simulated loss after create")
                    }) {}
                }.exceptionOrNull()
                assertTrue(failed is IOException)
                assertTrue(entry.matchesPayload())
                assertFalse(entry.hasReceipt())

                val reopened = checkNotNull(V2ArtifactStore(context).read(entry.route.id))
                assertEquals(entry.routeFingerprint, reopened.routeFingerprint)
                V2ArtifactDelivery.deliver(context, reopened, cfg, transport = { artifact, _, _ ->
                    assertArrayEquals(bytes, remote.getValue(artifact.route.path))
                    assertArrayEquals(bytes, artifact.payload.readBytes())
                }) {}
                assertTrue(reopened.hasReceipt())
                assertFalse(reopened.payload.exists())
                V2ArtifactDelivery.enqueue(context, reopened, cfg)
                assertEquals(initial.id, manager.getWorkInfosForUniqueWork(work).get(5, TimeUnit.SECONDS).single().id)
            }
            assertEquals(entries.map { it.route.path }.toSet(), remote.keys)
        } finally {
            SurveyDiagnosticAttribution.end(context, surveyId)
            entries.forEach {
                manager.cancelUniqueWork(V2ArtifactDelivery.workName(it)).result.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test fun slmTraceKeepsCorrelationAndOmitsRawPayloads() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        listOf(PromptPhase.EVAL, PromptPhase.FOLLOWUP).forEachIndexed { index, phase ->
            val observer = checkNotNull(V2SlmTrace.observer(context, surveyId, phase, "Q8", "answer-link"))
            observer(RepositoryTraceEvent.Started(index.toLong(), "synthetic model", "raw-test-prompt"))
            observer(RepositoryTraceEvent.Finished(index.toLong(), "raw-test-response", "failed", "raw-test-error", 55))
        }
        val traces = withTimeout(10_000) {
            var staged: List<V2ArtifactStore.Entry>
            do {
                staged = store.artifactIds().mapNotNull(store::read).filter {
                    it.route.kind == Kind.SLM && it.route.surveyId == surveyId
                }
                if (staged.size < 2) delay(25)
            } while (staged.size < 2)
            staged
        }
        assertTrue(traces.any { "_Q8_eval_" in it.route.basename })
        assertTrue(traces.any { "_Q8_followup_" in it.route.basename })
        traces.forEach {
            assertTrue(it.route.diagnosticsRedacted)
            val text = GZIPInputStream(it.payload.inputStream()).bufferedReader().use { reader -> reader.readText() }
            assertTrue("answer-link" in text)
            assertTrue("repositoryRequestId" in text)
            assertFalse("raw-test-" in text)
        }
    }

    @Test fun failedTracePublicationRecoversInTheSameProcessWithoutDuplicates() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        val writer = V2TraceArtifacts.writer(context, surveyId, Kind.SURVEY_TRACE)
        writer.append("""{"event":"synthetic"}""")
        val open = withTimeout(10_000) {
            val root = File(context.noBackupFilesDir, "v2_open_traces")
            var result: File?
            do {
                result = root.walkTopDown().firstOrNull { file ->
                    file.name == "events.open" && file.length() > 0 &&
                        runCatching { File(file.parentFile, "capture").readText().contains(surveyId) }.getOrDefault(false)
                }
                if (result == null) delay(25)
            } while (result == null)
            checkNotNull(result)
        }
        val id = open.parentFile!!.name
        V2TraceArtifacts.recover(context)
        assertTrue(open.isFile)
        assertNull(store.read(id))

        val blocker = File(context.noBackupFilesDir, "v2_artifacts/$id")
        blocker.parentFile!!.mkdirs()
        assertTrue(blocker.createNewFile())
        try {
            assertNull(writer.finalizeToGzip())
            assertTrue(open.isFile)
        } finally {
            assertTrue(blocker.delete())
        }

        V2TraceArtifacts.recover(context)
        val staged = checkNotNull(store.read(id))
        assertFalse(open.exists())
        assertTrue(staged.matchesPayload())
        V2TraceArtifacts.recover(context)
        assertEquals(staged.routeFingerprint, store.read(id)?.routeFingerprint)
        assertEquals(1, store.artifactIds().count { it == id })
    }

    @Test fun largeSurveyUsesRawReadBackAndCreateOnlyHttpRequests() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        val payload = """{"survey_id":"$surveyId","answer":"${"é".repeat(600_000)}"}"""
        val bytes = payload.toByteArray()
        assertTrue(bytes.size > 1_048_576)
        val config = GitHubUploader.GitHubConfig(
            V2ArtifactStore.OWNER, V2ArtifactStore.Profile.DEVELOPMENT.repo,
            "synthetic-only", "main", "", maxRawBytesHint = 2_000_000,
        )
        val path = "2026-11-08/survey/synthetic.json"
        for (putCode in listOf(201, 409, 422, null)) {
            var calls = 0
            var creates = 0
            GitHubUploader.uploadSurveyJsonIdempotentlyAtPath(
                config, path, surveyId, payload, verifyCreated = true, connect = { url ->
                    assertEquals("/repos/ishizuki-tech/SurveyExports-Dev/contents/$path", url.path)
                    when (++calls) {
                        1 -> contentsConnection(url, 404)
                        2 -> contentsConnection(url, putCode, """{"commit":{"sha":"test"}}""".toByteArray()) {
                            creates++
                            val body = org.json.JSONObject(it.toString(Charsets.UTF_8))
                            assertFalse(body.has("sha"))
                            assertEquals("main", body.getString("branch"))
                            assertArrayEquals(bytes, android.util.Base64.decode(body.getString("content"), android.util.Base64.DEFAULT))
                        }
                        3 -> contentsConnection(url, 200, bytes)
                        else -> error("Unexpected HTTP request $calls")
                    }
                },
            )
            assertEquals(1, creates)
            assertEquals(3, calls)
        }

        for (remote in listOf(bytes + byteArrayOf(32), bytes.copyOf().apply { this[lastIndex] = 0xff.toByte() })) {
            var calls = 0
            val failure = runCatching {
                GitHubUploader.uploadSurveyJsonIdempotentlyAtPath(
                    config, path, surveyId, payload, verifyCreated = true,
                    connect = { url -> calls++; contentsConnection(url, 200, remote) },
                )
            }.exceptionOrNull()
            assertTrue((failure as GitHubUploader.SurveyUploadValidationException).remoteConflict)
            assertEquals(1, calls)
        }
    }

    private fun contentsConnection(
        url: java.net.URL,
        code: Int?,
        body: ByteArray = byteArrayOf(),
        put: ((ByteArray) -> Unit)? = null,
    ) = object : java.net.HttpURLConnection(url) {
        private val written = java.io.ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream(): java.io.OutputStream = written
        override fun getResponseCode(): Int {
            if (put == null) {
                assertEquals("GET", requestMethod)
                assertEquals("ref=main", url.query)
                assertEquals("application/vnd.github.raw+json", getRequestProperty("Accept"))
            } else {
                assertEquals("PUT", requestMethod)
                assertNull(url.query)
                put.invoke(written.toByteArray())
            }
            return code ?: throw IOException("response lost after create")
        }
        override fun getInputStream(): java.io.InputStream = body.inputStream()
        override fun getErrorStream(): java.io.InputStream = body.inputStream()
    }

    /** Run on the disposable emulator before force-stopping the app. */
    @Test fun processRestart_writeOpenTracesAndPendingJson() = runBlocking {
        val surveyId = newTimeOrderedSurveyId()
        val survey = checkNotNull(SurveyTraceRegistry.start(context, surveyId))
        survey.event("SYNTHETIC_EVENT")
        val whisper = checkNotNull(WhisperTraceRegistry.start(context, surveyId))
        whisper.event("SYNTHETIC_WHISPER", "synthetic-answer", "Q8")
        val paths = store.paths(context, surveyId)
        val staged = store.stage(
            V2ArtifactStore.surveyKey(surveyId), Kind.SURVEY, surveyId, profile, paths.surveyJson()
        ) { output -> output.write(SurveyExportJsonBuilder.build(snapshot(surveyId), stamp).toByteArray()) }
        withTimeout(10_000) {
            val open = File(context.noBackupFilesDir, "v2_open_traces")
            while (open.walkTopDown().count { file ->
                file.name == "events.open" && file.isFile && file.readText().contains(surveyId)
            } < 2) delay(25)
        }
        restartProbe().writeText("$surveyId\n${Process.myPid()}\n${staged.routeFingerprint}")
    }

    @Test fun processRestart_readRecoveredTracesAndPendingJson() = runBlocking {
        val lines = restartProbe().readLines()
        val surveyId = lines[0]
        assertNotEquals(lines[1].toInt(), Process.myPid())
        V2TraceArtifacts.recover(context)
        val traces = store.artifactIds().mapNotNull(store::read).filter {
            it.route.surveyId == surveyId && it.route.kind in setOf(Kind.SURVEY_TRACE, Kind.WHISPER_TRACE)
        }
        assertEquals(2, traces.size)
        val json = checkNotNull(store.read(V2ArtifactStore.surveyKey(surveyId)))
        assertEquals(lines[2], json.routeFingerprint)
        assertTrue(json.matchesPayload())
        assertFalse(V2ArtifactDelivery.confirmed(context, json))
        traces.forEach {
            assertTrue(it.matchesPayload())
            assertTrue(it.route.basename.contains(surveyId))
            assertEquals(json.route.path.take(10), it.route.path.take(10))
            val text = GZIPInputStream(it.payload.inputStream()).bufferedReader().use { reader -> reader.readText() }
            assertTrue(text.contains("SYNTHETIC_"))
        }
    }

    @Test fun supportedExitRecordsStageAndDeliverWithoutNonCrashRingSnapshots() = runBlocking {
        val testContext = isolatedStorage(File(context.cacheDir, "issue155-exit-reasons").apply { mkdirs() })
        val records = V2ArtifactStore(testContext)
        val event = Instant.now()
        val reasons = listOf(ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_USER_REQUESTED)
        val cfg = checkNotNull(V2ArtifactDelivery.configForCredential(profile, "fixture"))
        reasons.forEach { reason ->
            CrashCapture.stageV2ExitRecord(testContext, testContext.filesDir, reason, 0, Process.myPid(),
                event, profile, null) { it.write("exit_reason=$reason".toByteArray()) }
            val id = V2ArtifactStore.stableId("exit|${event.toEpochMilli()}|${Process.myPid()}|$reason")
            val entry = records.read(id)
            if (reason == ApplicationExitInfo.REASON_USER_REQUESTED) {
                assertNull(entry)
            } else {
                checkNotNull(entry)
                V2ArtifactDelivery.deliver(testContext, entry, cfg, transport = { staged, _, _ ->
                    assertTrue(staged.payload.readText().endsWith("exit_reason=$reason"))
                }) {}
                assertTrue(entry.hasReceipt())
            }
            val ring = records.read(V2ArtifactStore.stableId("exit-ring|$id"))
            if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                assertTrue(checkNotNull(ring).matchesPayload())
            } else {
                assertNull(ring)
            }
        }
    }

    @Test fun correctionsRestart_writeFailedCrashAndUnsealedSurvey() {
        val probe = correctionsProbe()
        val fixture = File(probe, V2ArtifactStore.newId()).apply { mkdirs() }
        val testContext = isolatedStorage(fixture)
        val blockedStore = File(testContext.noBackupFilesDir, "v2_artifacts")
        blockedStore.writeText("block V2 staging")
        val report = CrashCapture.captureCrashToFile(
            testContext, testContext.filesDir, Thread.currentThread(), IllegalStateException("synthetic crash evidence"),
        )
        val marker = File(report.parentFile, "capture")
        assertTrue(report.isFile && marker.isFile)
        val metadata = Json.parseToJsonElement(marker.readText()).jsonObject
        assertTrue(File(report.parentFile, metadata.getValue("ringName").jsonPrimitive.content).length() > 0)
        assertTrue(blockedStore.delete())

        val surveyId = newTimeOrderedSurveyId()
        val paths = V2ArtifactStore(testContext).paths(testContext, surveyId)
        val partial = File(testContext.noBackupFilesDir, "v2_artifacts/.stage-${V2ArtifactStore.surveyKey(surveyId)}")
        assertTrue(partial.mkdir())
        File(partial, paths.surveyJson().basename).writeText("interrupted")
        File(probe, "probe").writeText("${Process.myPid()}\n$surveyId\n${report.absolutePath}\n${fixture.name}")
    }

    @Test fun correctionsRestart_readOriginalCrashAndRebuildSurvey() = runBlocking {
        val probe = correctionsProbe()
        val (pid, surveyId, path, fixture) = File(probe, "probe").readLines()
        val testContext = isolatedStorage(File(probe, fixture))
        assertNotEquals(pid.toInt(), Process.myPid())
        val report = File(path)
        val directory = report.parentFile!!
        val id = directory.name
        val capture = Json.parseToJsonElement(File(directory, "capture").readText()).jsonObject
        val header = report.readBytes().copyOf(capture.getValue("headerSize").jsonPrimitive.content.toInt())
        val ring = File(directory, capture.getValue("ringName").jsonPrimitive.content).readBytes()
        val records = V2ArtifactStore(testContext)
        assertNull(records.read(id))
        CrashCapture.recoverJavaCrashCaptures(testContext)
        val crash = checkNotNull(records.read(id))
        val snapshot = checkNotNull(records.read(V2ArtifactStore.stableId("crash-ring|$id")))
        assertArrayEquals(header, crash.payload.readBytes().takeLast(header.size).toByteArray())
        assertArrayEquals(ring, snapshot.payload.readBytes().takeLast(ring.size).toByteArray())
        assertFalse(directory.exists())
        CrashCapture.recoverJavaCrashCaptures(testContext)
        assertEquals(crash.routeFingerprint, records.read(id)?.routeFingerprint)

        val finalizer = SurveyUploadFinalizer(testContext)
        val tag = DeviceUploadTag("test")
        val result = finalizer.finalize(snapshot(surveyId), null, tag, stamp) as SurveyFinalizationResult.StagedPending
        assertEquals(surveyId, Json.parseToJsonElement(result.file.readText()).jsonObject.getValue("survey_id").jsonPrimitive.content)
        assertTrue(checkNotNull(records.read(V2ArtifactStore.surveyKey(surveyId))).matchesPayload())
        assertTrue(records.interruptedIds().isEmpty())
    }

    private fun correctionsProbe() = File(context.noBackupFilesDir, "issue155-corrections-probe").apply { mkdirs() }

    private fun isolatedStorage(root: File): Context = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
    }

    private fun restartProbe() = File(context.noBackupFilesDir, "issue155-restart-test")
    private fun snapshot(surveyId: String, voice: List<File> = emptyList(), questionId: String = "Q8") = SurveyFinalizationSnapshot(
        surveyId, mapOf(questionId to "Synthetic question"), mapOf(questionId to "Synthetic answer"),
        emptyMap(), voice.map { SurveyViewModel.AudioRef(surveyId, questionId, it.name) }, "{}", emptyMap(),
    )
}
