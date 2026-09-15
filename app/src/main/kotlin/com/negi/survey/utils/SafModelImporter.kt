package com.negi.survey.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID

/** Copies a user-selected legacy model into private storage only after verification. */
object SafModelImporter {
    private const val TAG = "SafModelImporter"

    data class ModelIdentity(
        val fileName: String,
        val expectedBytes: Long,
        val sha256: String,
    )

    sealed interface Result {
        data class Imported(val file: File) : Result
        data class Rejected(val reason: String) : Result
        data class ExistingPrivateModel(val file: File) : Result
    }

    suspend fun importUri(
        context: Context,
        uri: Uri,
        destination: File,
        identity: ModelIdentity,
    ): Result {
        val resolver = context.contentResolver
        val metadata = readMetadata(resolver, uri)
        Log.i(TAG, "import requested: uriScheme=${uri.scheme} uriAuthority=${uri.authority} " +
            "displayName=${metadata.displayName ?: "<unknown>"} reportedBytes=${metadata.size ?: -1L}")

        val displayName = metadata.displayName
        if (displayName != null && !isCompatibleName(displayName, identity.fileName)) {
            Log.w(TAG, "import rejected: incompatible display name")
            return Result.Rejected("Selected file is not the expected LiteRT model.")
        }
        if (metadata.size != null && metadata.size != identity.expectedBytes) {
            Log.w(TAG, "import rejected: reported size=${metadata.size} expected=${identity.expectedBytes}")
            return Result.Rejected("Selected model has the wrong size.")
        }

        val input = runCatching { resolver.openInputStream(uri) }
            .onFailure { Log.w(TAG, "import rejected: unable to open selected URI", it) }
            .getOrNull()
            ?: return Result.Rejected("Unable to read the selected model.")

        return input.use { importStream(it, destination, identity) }
    }

    internal suspend fun importStream(
        input: InputStream,
        destination: File,
        identity: ModelIdentity,
        beforePromotion: (suspend () -> Unit)? = null,
    ): Result {
        if (destination.exists() && destination.length() == identity.expectedBytes) {
            Log.i(TAG, "import skipped: valid-sized private destination already exists")
            return Result.ExistingPrivateModel(destination)
        }

        val parent = destination.parentFile
            ?: return Result.Rejected("Private model directory is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) {
            return Result.Rejected("Unable to create private model directory.")
        }

        val temp = File(parent, ".${destination.name}.${UUID.randomUUID()}.import.tmp")
        try {
            Log.i(TAG, "copy started: destination=${destination.name}")
            temp.outputStream().buffered().use { out -> input.copyTo(out, bufferSize = 1 shl 20) }
            Log.i(TAG, "copy completed: bytes=${temp.length()}")

            if (temp.length() != identity.expectedBytes) {
                Log.w(TAG, "SHA verification skipped: copied size=${temp.length()} expected=${identity.expectedBytes}")
                return Result.Rejected("Selected model has the wrong size.")
            }

            val actualSha = sha256(temp)
            if (!actualSha.equals(identity.sha256, ignoreCase = true)) {
                Log.w(TAG, "SHA verification failed")
                return Result.Rejected("Selected model failed integrity verification.")
            }
            Log.i(TAG, "SHA verification succeeded")

            beforePromotion?.invoke()

            return ModelDestinationLock.withLock(destination) {
                if (destination.exists()) {
                    Log.w(TAG, "import rejected: private destination already exists at promotion")
                    return@withLock Result.Rejected(
                        "A private model is already available. The selected file was not imported."
                    )
                }
                val promoted = runCatching { Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE) }
                    .onFailure { Log.w(TAG, "import rejected: atomic promotion failed", it) }
                    .getOrNull()
                if (promoted == null) {
                    Log.w(TAG, "import rejected: atomic promotion failed")
                    return@withLock Result.Rejected("Unable to save the verified model privately.")
                }
                Log.i(TAG, "import accepted: destination=${destination.name}")
                Result.Imported(destination)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "import rejected: copy failed", t)
            return Result.Rejected("Unable to copy the selected model.")
        } finally {
            if (temp.exists() && !temp.delete()) {
                Log.w(TAG, "temp cleanup failed")
            }
        }
    }

    private data class Metadata(val displayName: String?, val size: Long?)

    private fun readMetadata(resolver: ContentResolver, uri: Uri): Metadata {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use Metadata(null, null)
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let(cursor::getString)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let(cursor::getLong)
                    Metadata(name, size)
                } ?: Metadata(null, null)
        }.getOrElse {
            Log.w(TAG, "selected URI metadata unavailable", it)
            Metadata(null, null)
        }
    }

    private fun isCompatibleName(selected: String, expected: String): Boolean {
        if (selected == expected) return true
        val extension = expected.substringAfterLast('.', missingDelimiterValue = "")
        val base = expected.removeSuffix(if (extension.isEmpty()) "" else ".${extension}")
        return extension.isNotEmpty() && Regex("^${Regex.escape(base)} \\(\\d+\\)\\.${Regex.escape(extension)}$")
            .matches(selected)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
