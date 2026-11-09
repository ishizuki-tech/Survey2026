package com.negi.survey.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.negi.survey.net.V2ArtifactStore
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exclusive active intervals are scoped to a process incarnation, including across PID reuse. */
internal object SurveyDiagnosticAttribution {
    val processSession = V2ArtifactStore.newId()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val active = mutableMapOf<String, Interval>()
    private val history = mutableListOf<Interval>()
    private var advertised: Interval? = null

    @Serializable
    data class Interval(
        val surveyId: String,
        val processSession: String,
        val pid: Int,
        val startMillis: Long,
        val startElapsed: Long,
        val endMillis: Long? = null,
        val exclusive: Boolean = true,
    )

    @Synchronized
    fun registerProcess(context: Context) {
        if (active.isEmpty()) setExitSummary(context, null)
    }

    @Synchronized
    fun begin(context: Context, surveyId: String) {
        if (surveyId in active) return
        val interval = Interval(surveyId, processSession, Process.myPid(), System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), exclusive = active.isEmpty())
        if (active.isNotEmpty()) {
            revokeAdvertisement(context)
            active.replaceAll { _, old -> old.copy(exclusive = false) }
            active.values.forEach { persist(context, it) }
        }
        active[surveyId] = interval
        persist(context, interval, advertise = interval.exclusive)
    }

    @Synchronized
    fun end(context: Context, surveyId: String) {
        val interval = active[surveyId] ?: return
        if (advertised === interval) revokeAdvertisement(context)
        active.remove(surveyId)
        val now = System.currentTimeMillis()
        val clockConsistent = abs((now - interval.startMillis) -
            (SystemClock.elapsedRealtime() - interval.startElapsed)) < 2_000
        val closed = interval.copy(endMillis = now, exclusive = interval.exclusive && clockConsistent)
        history += closed
        persist(context, closed)
    }

    @Synchronized
    fun activeStart(): Instant? = active.values.singleOrNull()
        ?.takeIf { it.exclusive }
        ?.let { Instant.ofEpochMilli(it.startMillis) }

    @Synchronized
    fun currentProcessEvidence(start: Instant, end: Instant): Interval? {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        return exclusiveInterval(history + active.values, processSession, Process.myPid(),
            start.toEpochMilli(), end.toEpochMilli(), now)?.takeIf { interval ->
            interval.endMillis != null || abs((now - interval.startMillis) -
                (elapsed - interval.startElapsed)) < 2_000
        }
    }

    fun exitEvidence(context: Context, processSummary: ByteArray?, pid: Int, time: Instant): Interval? =
        exitEvidence(processSummary, pid, time) { session, survey ->
            Json.decodeFromString(Interval.serializer(), evidenceFile(context, session, survey).readText())
        }

    internal fun exitEvidence(
        processSummary: ByteArray?, pid: Int, time: Instant,
        read: (session: String, survey: String) -> Interval?,
    ): Interval? {
        val values = processSummary?.toString(Charsets.UTF_8)?.split('|') ?: return null
        // Older summaries may have advertised the interval before its evidence was durable.
        if (values.size != 5 || values[0] != "v3" || values[1] == processSession) return null
        if (!values[1].matches(uuidPattern) || !values[2].matches(uuidPattern) || exitProfile(processSummary) == null) return null
        val interval = runCatching { read(values[1], values[2]) }.getOrNull() ?: return null
        if (interval.surveyId != values[2] || interval.endMillis != null ||
            interval.startElapsed != values[4].toLongOrNull()) return null
        val stamp = time.toEpochMilli()
        return exclusiveInterval(listOf(interval), values[1], pid, stamp, stamp, stamp)
    }

    fun exitProfile(processSummary: ByteArray?): V2ArtifactStore.Profile? {
        val values = processSummary?.toString(Charsets.UTF_8)?.split('|') ?: return null
        val validVersion = (values.size == 4 && values[0] == "v2") || (values.size == 5 && values[0] == "v3")
        if (!validVersion || !values[1].matches(uuidPattern)) return null
        return V2ArtifactStore.Profile.entries.firstOrNull { it.name == values[3] }
    }

    /** A bounded capture must fit completely inside exactly one exclusive interval. */
    fun exclusiveInterval(
        intervals: Collection<Interval>,
        session: String,
        pid: Int,
        start: Long,
        end: Long,
        observation: Long,
    ): Interval? {
        if (start > end || end > observation) return null
        val overlapping = intervals.filter {
            it.processSession == session && it.pid == pid &&
                it.startMillis <= end && (it.endMillis ?: observation) >= start
        }
        return overlapping.singleOrNull()?.takeIf {
            it.exclusive && it.startMillis <= start && (it.endMillis ?: observation) >= end
        }
    }

    private fun persist(context: Context, interval: Interval, advertise: Boolean = false) {
        scope.launch {
            if (!writeEvidence(context, interval)) return@launch
            synchronized(this@SurveyDiagnosticAttribution) {
                if (advertise && advertised == null && active.values.singleOrNull() === interval) {
                    // Even a failed IPC may have reached the service. Revoke conservatively.
                    advertised = interval
                    setExitSummary(context, interval)
                } else if (!interval.exclusive || interval.endMillis != null) {
                    val previous = advertised
                    if (previous?.surveyId == interval.surveyId && previous.startElapsed == interval.startElapsed) {
                        advertised = null
                    }
                }
            }
        }
    }

    private fun writeEvidence(context: Context, interval: Interval): Boolean = runCatching {
        val file = evidenceFile(context, interval.processSession, interval.surveyId)
        java.nio.file.Files.createDirectories(file.parentFile!!.toPath())
        V2ArtifactStore.writeAtomically(file, Json.encodeToString(Interval.serializer(), interval))
        V2ArtifactStore.syncDirectory(file.parentFile!!.parentFile!!)
        V2ArtifactStore.syncDirectory(context.noBackupFilesDir)
    }.onFailure { DiagnosticContentPolicy.logWarning("DiagnosticAttribution", "Interval persistence failed", it) }.isSuccess

    // Invalidate before changing the active set. Disk is the fallback when Android rejects
    // a summary update (for example, throttling). The published open write has already finished.
    private fun revokeAdvertisement(context: Context) {
        val interval = advertised ?: return
        if (setExitSummary(context, null) || writeEvidence(context, interval.copy(exclusive = false))) {
            advertised = null
        }
    }

    private fun setExitSummary(context: Context, interval: Interval?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            checkNotNull(context.getSystemService(ActivityManager::class.java)).setProcessStateSummary(
                "v3|$processSession|${interval?.surveyId.orEmpty()}|${V2ArtifactStore.profile.name}|${interval?.startElapsed ?: ""}"
                    .toByteArray(Charsets.UTF_8)
            )
        }.onFailure { DiagnosticContentPolicy.logWarning("DiagnosticAttribution", "Exit correlation unavailable", it) }.isSuccess
    }

    private fun evidenceFile(context: Context, session: String, survey: String): File =
        File(context.noBackupFilesDir, "v2_attribution/$session/$survey")

    private val uuidPattern = Regex("[0-9a-f-]{36}")
}
