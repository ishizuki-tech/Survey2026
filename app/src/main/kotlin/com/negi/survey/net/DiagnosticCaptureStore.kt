package com.negi.survey.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Publishes an immutable capture before any remote write. Incomplete captures were never sent. */
internal class DiagnosticCaptureStore(private val directory: File) {
    suspend fun capture(prepare: suspend (File) -> Unit): List<File> {
        val marker = File(directory, ".complete")
        if (!marker.exists()) {
            directory.deleteRecursively()
            check(directory.mkdirs()) { "Cannot create diagnostic capture directory" }
            prepare(directory)
            val entries = payloads().joinToString("\n") { "${fingerprint(it)} ${it.name}" }
            val temp = File(directory, ".complete.tmp")
            FileOutputStream(temp).use { it.write(entries.toByteArray()); it.fd.sync() }
            check(temp.renameTo(marker)) { "Cannot publish diagnostic capture" }
        }
        val entries = marker.readLines().filter { it.isNotBlank() }
        val files = entries.map { entry ->
            val file = File(directory, entry.substringAfter(' '))
            if (file.parentFile != directory || !file.isFile || fingerprint(file) != entry.substringBefore(' ')) {
                throw IOException("Durable diagnostic capture is missing or changed; retained for inspection")
            }
            file
        }
        return files
    }

    private fun payloads() = directory.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith('.') }.sortedBy { it.name }

    companion object {
        /** A Worker returning success is insufficient: WorkManager must have committed it. */
        fun cleanupConfirmed(directory: File, confirmed: Boolean) {
            if (confirmed && directory.walkTopDown().none { it.name == ".retain" }) directory.deleteRecursively()
        }

        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        fun write(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
    }
}
