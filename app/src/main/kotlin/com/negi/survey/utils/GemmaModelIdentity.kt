package com.negi.survey.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Trusted byte identity, supplied explicitly for synthetic artifacts in tests. */
data class ModelArtifactIdentity(
    val repository: String,
    val revision: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelArtifactIdentity>
    init {
        require(size > 0)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
    }
    val downloadUrl: String get() = "https://huggingface.co/$repository/resolve/$revision/$fileName"

    /** Read-only verification; cancellation and destination ownership are checked per chunk. */
    suspend fun validate(file: File) = withContext(Dispatchers.IO) {
        checkActiveOwner()
        if (!file.isFile) throw IOException("Model file cannot be read")
        if (file.length() != size) throw ModelIntegrityException("Model size mismatch: expected $size bytes, got ${file.length()}")
        validateStream { file.inputStream() }
    }

    /** Verifies provider bytes, rather than trusting MediaStore's reported size. */
    suspend fun validateStream(open: () -> InputStream?) = withContext(Dispatchers.IO) {
        checkActiveOwner()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        (open() ?: throw IOException("Model stream cannot be read")).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActiveOwner()
                val n = input.read(buffer)
                if (n == -1) break
                count += n
                if (count > size) throw ModelIntegrityException("Model size mismatch: expected $size bytes")
                digest.update(buffer, 0, n)
            }
        }
        checkActiveOwner()
        if (count != size) throw ModelIntegrityException("Model size mismatch: expected $size bytes, got $count")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256, ignoreCase = true)) throw ModelIntegrityException("Model SHA-256 mismatch; select the configured model or download it again")
    }

    private suspend fun checkActiveOwner() {
        val context = currentCoroutineContext()
        context.ensureActive()
        context[ModelArtifactOperations.Lease]?.checkCurrent()
    }
}

class ModelIntegrityException(message: String) : IOException(message)

/** Official HF revision API LFS metadata, verified in STEP120A. No model upgrade. */
object GemmaModelIdentity {
    val trusted = ModelArtifactIdentity(
        repository = "google/gemma-3n-E4B-it-litert-lm",
        revision = "297ed75955702dec3503e00c2c2ecbbf475300bc",
        fileName = "gemma-3n-E4B-it-int4.litertlm",
        size = 4919541760L,
        sha256 = "2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4",
    )

    fun forFile(name: String): ModelArtifactIdentity {
        require(name == trusted.fileName) { "Unsupported model filename; supply an explicit trusted model identity" }
        return trusted
    }

    fun resolve(url: String): ModelArtifactIdentity {
        require(url == trusted.downloadUrl) {
            "Unsupported model configuration. Use the pinned Gemma model URL or supply an explicit trusted model identity."
        }
        return trusted
    }
}
