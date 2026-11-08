package com.negi.survey.net

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface V2SurveyDeliveryLedger {
    enum class Evidence { MISSING, CONFIRMED, CONFLICT }

    fun inspect(route: V2SurveyJsonRoute): Evidence
    fun recordConfirmed(route: V2SurveyJsonRoute)
}

/** Separate, durable delivery evidence. The existing survey ledger is read only. */
internal class V2SurveyJsonLedger(
    private val appFilesDir: File,
    private val historicalUploaded: (String) -> Boolean
) : V2SurveyDeliveryLedger {
    @Serializable
    private data class Record(
        val formatVersion: Int,
        val protocol: String,
        val surveyUuid: String,
        val payloadSha256: String,
        val destination: V2SurveyJsonDestination,
        val remoteTargetPath: String
    )

    override fun inspect(route: V2SurveyJsonRoute): V2SurveyDeliveryLedger.Evidence =
        synchronized(lock) { inspectLocked(route) }

    override fun recordConfirmed(route: V2SurveyJsonRoute) = synchronized(lock) {
        when (inspectLocked(route)) {
            V2SurveyDeliveryLedger.Evidence.CONFIRMED -> return@synchronized
            V2SurveyDeliveryLedger.Evidence.CONFLICT -> throw V2LedgerConflict()
            V2SurveyDeliveryLedger.Evidence.MISSING -> Unit
        }
        val directory = directory(appFilesDir)
        if (directory.attributesOrNull() == null) Files.createDirectory(directory)
        syncDirectory(directory.parent)
        val temporary = Files.createTempFile(directory, ".confirmed-", ".tmp")
        try {
            temporary.toFile().outputStream().use { output ->
                output.write(recordBytes(route))
                output.fd.sync()
            }
            Files.move(temporary, directory.resolve("${route.surveyUuid}.json"), ATOMIC_MOVE)
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun inspectLocked(route: V2SurveyJsonRoute): V2SurveyDeliveryLedger.Evidence {
        route.validate()
        val historical = try {
            historicalUploaded(route.surveyUuid)
        } catch (_: ClassCastException) {
            return V2SurveyDeliveryLedger.Evidence.CONFLICT
        }
        if (historical) return V2SurveyDeliveryLedger.Evidence.CONFLICT
        val file = directory(appFilesDir).resolve("${route.surveyUuid}.json")
        val attributes = file.attributesOrNull() ?: return V2SurveyDeliveryLedger.Evidence.MISSING
        if (!attributes.isRegularFile || !Files.readAllBytes(file).contentEquals(recordBytes(route))) {
            return V2SurveyDeliveryLedger.Evidence.CONFLICT
        }
        // If publication previously failed during directory sync, do not treat visible bytes as
        // confirmed until that sync (and the namespace's parent sync) has succeeded.
        syncDirectory(file.parent.parent)
        syncDirectory(file.parent)
        return V2SurveyDeliveryLedger.Evidence.CONFIRMED
    }

    private fun recordBytes(route: V2SurveyJsonRoute): ByteArray = Json.encodeToString(
        Record(1, route.protocol, route.surveyUuid, route.payloadSha256, route.destination, route.remoteTargetPath)
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val NAMESPACE = "v2_survey_json_ledger"
        private val lock = Any()
        private val canonicalUuidV7 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        private fun directory(appFilesDir: File) = appFilesDir.toPath().toRealPath().resolve(NAMESPACE).also {
            val attributes = it.attributesOrNull()
            if (attributes != null && !attributes.isDirectory) throw IOException("Invalid V2 ledger directory")
        }

        /** Older protocols are fenced even by a corrupt V2 record. Called while claiming authority. */
        fun hasV2Evidence(appFilesDir: File, normalizedId: String): Boolean {
            // Historical survey IDs can be arbitrary strings. Only canonical V2 UUIDs have ledger files.
            if (!canonicalUuidV7.matches(normalizedId)) return false
            return directory(appFilesDir).resolve("$normalizedId.json").attributesOrNull() != null
        }
    }
}

internal class V2LedgerConflict : IllegalStateException("Conflicting survey delivery evidence")
