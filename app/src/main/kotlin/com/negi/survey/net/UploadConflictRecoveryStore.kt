package com.negi.survey.net

import androidx.work.Data
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** Retains exhausted transient/collision work; payloads stay in their existing staging paths. */
internal class UploadConflictRecoveryStore(private val filesDir: File) {
    data class Record(val key: String, val input: Data, val exhausted: Int, val recoveryId: UUID, val dispatched: Boolean)
    private val directory get() = File(filesDir, "upload_conflict_recovery")

    fun get(key: String): Record? = synchronized(LOCK) {
        val file = file(key)
        if (!file.exists()) return@synchronized null
        read(file)
    }

    fun records(): List<Record> = synchronized(LOCK) {
        directory.listFiles().orEmpty().filter { it.extension == "properties" }.map(::read)
    }

    fun exhaust(key: String, input: Data, permanent: Boolean = false): Record = synchronized(LOCK) {
        val previous = get(key)
        val record = Record(key, input, if (permanent || input.getBoolean(RECOVERY, false)) 2 else 1,
            previous?.recoveryId ?: UUID.randomUUID(), false)
        // A stale request must never reopen an exhausted recovery budget.
        write(if (previous != null && previous.exhausted >= record.exhausted) previous else record)
    }

    fun dispatched(record: Record) = synchronized(LOCK) {
        val current = get(record.key) ?: return@synchronized
        if (current.exhausted == 1) write(current.copy(dispatched = true))
    }

    /** The persisted request ID closes the enqueue/acknowledgement process-death window. */
    fun recoverOnce(record: Record, alreadyExists: (UUID) -> Boolean, enqueue: (Record) -> Unit): Boolean = synchronized(LOCK) {
        val current = get(record.key) ?: return@synchronized false
        if (current.exhausted >= 2 || current.dispatched || current.key.startsWith("survey:")) return@synchronized false
        if (!alreadyExists(current.recoveryId)) enqueue(current)
        dispatched(current)
        true
    }

    private fun read(file: File): Record {
        val p = Properties().apply { file.inputStream().use { load(it) } }
        return Record(p.getProperty("key"), Data.fromByteArray(Base64.getDecoder().decode(p.getProperty("input"))),
            p.getProperty("exhausted").toInt(), UUID.fromString(p.getProperty("recoveryId")), p.getProperty("dispatched").toBooleanStrict())
    }

    private fun write(record: Record): Record {
        directory.mkdirs()
        val p = Properties().apply {
            setProperty("key", record.key)
            setProperty("input", Base64.getEncoder().encodeToString(record.input.toByteArray()))
            setProperty("exhausted", record.exhausted.toString())
            setProperty("recoveryId", record.recoveryId.toString())
            setProperty("dispatched", record.dispatched.toString())
        }
        val target = file(record.key)
        val temp = File(directory, target.name + ".tmp")
        FileOutputStream(temp).use { p.store(it, "Exhausted upload; staged data retained"); it.fd.sync() }
        check(temp.renameTo(target)) { "Cannot persist upload conflict state" }
        return record
    }

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$hash.properties")
    }

    companion object {
        private val LOCK = Any()
        const val RECOVERY = "conflict.recovery"
        const val CAPTURE_ID = "conflict.captureId"
        const val DATE = "conflict.date"
        fun surveyKey(id: String) = "survey:" + id.trim().lowercase(java.util.Locale.US)
        fun fileKey(file: File) = "file:" + file.absolutePath
        fun key(input: Data, workerId: UUID): String = when {
            input.getString(GitHubUploadWorker.KEY_UPLOAD_KIND) == GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON ->
                surveyKey(input.getString(GitHubUploadWorker.KEY_SURVEY_ID).orEmpty())
            !input.getString(GitHubUploadWorker.KEY_FILE_PATH).isNullOrBlank() ->
                fileKey(File(input.getString(GitHubUploadWorker.KEY_FILE_PATH)!!))
            else -> "capture:" + (input.getString(CAPTURE_ID) ?: workerId.toString())
        }
    }
}
