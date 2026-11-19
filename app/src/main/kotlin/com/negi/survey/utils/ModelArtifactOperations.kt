package com.negi.survey.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local ownership of model artifacts. The app has a single Android process.
 *
 * Shared resumable files and persistence are serialized on IO, never on the UI thread.
 * The short monitor linearizes supersession with atomic promotion/state publication;
 * it must never enclose a stream copy, network transfer, or native call.
 */
object ModelArtifactOperations {
    internal class Destination {
        val mutex = Mutex()
        var current: Lease? = null
    }

    class Lease internal constructor(
        val file: File,
        private val destination: Destination,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
        @Volatile private var job: Job? = null
        private var revoked = false

        fun attach(job: Job) { this.job = job }
        fun cancel() = synchronized(destination) { revoked = true }

        /** Short, non-suspending mutation boundary; rejects canceled and superseded owners. */
        fun <T> publish(block: () -> T): T = synchronized(destination) {
            checkCurrent()
            block()
        }

        fun checkCurrent() = synchronized(destination) {
            if (revoked || destination.current !== this || job?.isActive == false) {
                throw CancellationException("Model artifact operation superseded or canceled")
            }
        }

        /** A completed installation may be discarded only while it is still the current owner. */
        suspend fun <T> recover(block: suspend (Lease) -> T): T {
            val recoveryJob = currentCoroutineContext()[Job]
            val recovery = synchronized(destination) {
                if (revoked || destination.current !== this || job?.isCancelled == true) {
                    throw CancellationException("Installation has been replaced")
                }
                Lease(file, destination).also {
                    recoveryJob?.let(it::attach)
                    destination.current = it
                }
            }
            return recovery.locked { block(recovery) }
        }

        suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO + this) {
            if (currentCoroutineContext()[Held]?.lease === this@Lease) {
                checkCurrent()
                block().also {
                    currentCoroutineContext().ensureActive()
                    checkCurrent()
                }
            } else {
                destination.mutex.withLock {
                    checkCurrent()
                    withContext(Held(this@Lease)) {
                        block().also {
                            currentCoroutineContext().ensureActive()
                            checkCurrent()
                        }
                    }
                }
            }
        }

        /** Stage outside the destination lock: a blocked old stream cannot corrupt a new import. */
        suspend fun import(
            open: () -> InputStream?,
            installed: suspend (File) -> Unit,
        ) = withContext(Dispatchers.IO + this) {
            checkCurrent()
            file.parentFile?.mkdirs()
            val staging = File.createTempFile("${file.name}.".padEnd(3, '_'), ".fromPicker.tmp", file.parentFile)
            try {
                (open() ?: throw IOException("Couldn't read the selected file")).use { input ->
                    staging.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
                locked {
                    promote(staging)
                    installed(file)
                    checkCurrent()
                }
            } finally {
                Files.deleteIfExists(staging.toPath())
            }
        }

        /** Provider rows stay pending until copied; failure/cancellation removes only this row.
         * Called under [locked], so a replacement cannot install/persist until cleanup completes.
         * Provider calls run outside the short monitor and cannot block UI ownership registration.
         */
        suspend fun <T> persist(
            create: () -> T,
            write: suspend (T) -> Unit,
            commit: (T) -> Unit,
            cleanup: (T) -> Unit,
        ) {
            checkCurrent()
            val row = create()
            try {
                write(row)
                currentCoroutineContext().ensureActive()
                checkCurrent()
                commit(row)
                checkCurrent()
            } catch (t: Throwable) {
                try {
                    cleanup(row)
                } catch (cleanupFailure: Throwable) {
                    t.addSuppressed(cleanupFailure)
                }
                throw t
            }
        }

        /** All candidates are siblings of the destination. No copy-to-final fallback is safe.
         * Unsupported atomic replacement fails closed, preserving the old installed artifact.
         */
        suspend fun promote(candidate: File, move: (File, File) -> Unit = ::atomicReplace) {
            val context = currentCoroutineContext()
            require(candidate.canonicalFile.parentFile == file.parentFile) { "Candidate must be a sibling" }
            if (!candidate.isFile || candidate.length() <= 0L) throw IOException("Empty model candidate")
            publish {
                context.ensureActive()
                move(candidate, file)
            }
        }
    }

    private class Held(val lease: Lease) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private val destinations = ConcurrentHashMap<String, Destination>()

    /** Claims are non-blocking with respect to ongoing stream I/O. Latest explicit operation wins. */
    fun begin(file: File): Lease {
        val canonical = file.canonicalFile
        val destination = destinations.computeIfAbsent(canonical.path) { Destination() }
        return synchronized(destination) {
            Lease(canonical, destination).also { destination.current = it }
        }
    }

    /** Entry point for lower-level download/restore callers, reusing the caller's lease if present. */
    suspend fun <T> withDestination(file: File, block: suspend () -> T): T {
        val context = currentCoroutineContext()
        val inherited = context[Lease]?.takeIf { it.file == file.canonicalFile }
        val lease = inherited ?: begin(file).also { context[Job]?.let(it::attach) }
        return lease.locked(block)
    }

    suspend fun current(): Lease = currentCoroutineContext()[Lease]
        ?: error("Model artifact operation requires destination ownership")

    private fun atomicReplace(candidate: File, destination: File) {
        Files.move(candidate.toPath(), destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
