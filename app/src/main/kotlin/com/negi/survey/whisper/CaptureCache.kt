package com.negi.survey.whisper

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Owns only capture scratch files, never V2 packages or legacy recording cache files.
 * A lease spans PCM capture, WAV conversion, transcription, and recorder closure.
 * Its OS file lock survives coroutine cancellation and is released by process death.
 */
internal class CaptureCache(
    cacheDirectory: File,
    private val generation: String = processGeneration,
    private val delete: (Path) -> Boolean = { Files.deleteIfExists(it) },
    private val diagnostic: (String) -> Unit = {},
) {
    private val cache = cacheDirectory.canonicalFile.toPath()
    private val root = cache.resolve("capture-cache-v1")
    private val active = mutableMapOf<String, Capture>()
    private var scan: DirectoryStream<Path>? = null
    private var cursor: Iterator<Path>? = null

    init { require(uuid(generation)) }

    /** Bounded, off-main-thread admission work; partial/unknown entries always fail closed. */
    @Synchronized
    fun begin(recordingId: String = UUID.randomUUID().toString()): Capture {
        require(uuid(recordingId))
        Files.createDirectories(cache)
        try { Files.createDirectory(root) } catch (_: FileAlreadyExistsException) { /* Revalidate below. */ }
        check(directory(root) && root.toFile().canonicalFile.toPath() == root) { "Invalid managed capture namespace" }
        try {
            sweep()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            diagnostic("Capture cache scan deferred")
            closeScan()
        }
        val entry = root.resolve(recordingId)
        Files.createDirectory(entry) // IDs and namespaces are never reopened or reused.
        val lockPath = entry.resolve(LOCK)
        Files.createFile(lockPath)
        val channel = FileChannel.open(lockPath, READ, WRITE)
        val lock = try { checkNotNull(channel.tryLock()) } catch (failure: Exception) {
            channel.close()
            throw failure
        }
        try {
            val pending = entry.resolve("owner.pending").toFile()
            FileOutputStream(pending).use {
                it.write("capture-cache-v1\n$recordingId\n$generation\n".toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(pending.toPath(), entry.resolve(OWNER), ATOMIC_MOVE)
            // A committed owner exists before either payload can be created.
            val wav = Files.createFile(entry.resolve(WAV)).toFile()
            val pcm = Files.createFile(entry.resolve(PCM)).toFile()
            Files.createFile(entry.resolve(READY))
            return Capture(recordingId, wav, pcm, entry, channel, lock).also { active[recordingId] = it }
        } catch (failure: Exception) {
            lock.release()
            channel.close()
            // Preserve partially created entries; the scanner must validate their evidence.
            throw failure
        }
    }

    private fun sweep() {
        if (scan == null) {
            scan = Files.newDirectoryStream(root)
            cursor = scan!!.iterator()
        }
        repeat(MAX_ENTRIES_PER_ADMISSION) {
            val iterator = cursor ?: return
            if (!iterator.hasNext()) { closeScan(); return }
            val entry = iterator.next()
            try { reclaim(entry) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                diagnostic("Capture cache entry retained")
            }
        }
    }

    private fun reclaim(entry: Path) {
        val id = entry.fileName.toString()
        if (!uuid(id) || active.containsKey(id) || !directory(entry)) return
        val owner = owner(entry, id) ?: return
        if (owner == generation) return
        if (!regular(entry.resolve(LOCK))) return
        FileChannel.open(entry.resolve(LOCK), READ, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } ?: return
            lock.use {
                // Revalidate after lock acquisition. No admission ever reopens this entry.
                if (owner(entry, id) != owner || !knownChildren(entry)) return
                val wav = entry.resolve(WAV)
                // Nonempty WAVs may be completed evidence awaiting staging. Keep them intact.
                if (!Files.notExists(wav, NOFOLLOW_LINKS) && (!regular(wav) || Files.size(wav) != 0L)) return
                val pcm = entry.resolve(PCM)
                if (!Files.notExists(pcm, NOFOLLOW_LINKS) && !regular(pcm)) return
                if (!remove(pcm) || !remove(wav)) return
                if (!remove(entry.resolve(READY)) || !remove(entry.resolve(OWNER))) return
                if (!remove(entry.resolve(LOCK))) return
                remove(entry)
            }
        }
    }

    private fun owner(entry: Path, id: String): String? {
        if (!regular(entry.resolve(READY)) || Files.size(entry.resolve(READY)) != 0L) return null
        val path = entry.resolve(OWNER)
        if (!regular(path) || Files.size(path) !in 1..MAX_OWNER_BYTES) return null
        val lines = Files.readAllLines(path, Charsets.UTF_8)
        if (lines.size != 3 || lines[0] != "capture-cache-v1" || lines[1] != id || !uuid(lines[2])) return null
        return lines[2]
    }

    private fun knownChildren(entry: Path): Boolean = Files.newDirectoryStream(entry).use { children ->
        var count = 0
        children.all { ++count <= 5 && it.fileName.toString() in names }
    }

    private fun remove(path: Path): Boolean {
        if (Files.notExists(path, NOFOLLOW_LINKS)) return true
        return try {
            (delete(path) || Files.notExists(path, NOFOLLOW_LINKS)).also {
                if (!it) diagnostic("Capture cache deletion deferred")
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            diagnostic("Capture cache deletion deferred")
            false
        }
    }

    private fun closeScan() {
        val previous = scan
        scan = null
        cursor = null
        try { previous?.close() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            diagnostic("Capture cache scan close deferred")
        }
    }

    internal inner class Capture internal constructor(
        val id: String,
        val wav: File,
        val pcm: File,
        private val entry: Path,
        private val channel: FileChannel,
        private val lock: FileLock,
    ) : Closeable {
        private var closed = false

        /** Only the lifecycle owner may call this, after every reader/writer and close has ended. */
        fun finish() = synchronized(this@CaptureCache) {
            if (closed) return@synchronized
            try {
                val payloadsKnown = listOf(pcm.toPath(), wav.toPath()).all {
                    Files.notExists(it, NOFOLLOW_LINKS) || regular(it)
                }
                if (owner(entry, id) == generation && knownChildren(entry) && payloadsKnown &&
                    remove(pcm.toPath()) && remove(wav.toPath())) {
                    if (remove(entry.resolve(READY)) && remove(entry.resolve(OWNER)) && remove(entry.resolve(LOCK))) remove(entry)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                diagnostic("Capture cache release retained files")
            } finally {
                close()
            }
        }

        /** Release ownership without deleting bytes; useful for modeling abrupt process loss. */
        override fun close() = synchronized(this@CaptureCache) {
            if (closed) return@synchronized
            lock.release()
            channel.close()
            closed = true
            active.remove(id, this)
        }
    }

    companion object {
        private const val READY = "ready"
        private const val OWNER = "owner"
        private const val LOCK = "owner.lock"
        private const val WAV = "capture.wav"
        private const val PCM = "capture.pcm"
        private const val MAX_OWNER_BYTES = 256L
        internal const val MAX_ENTRIES_PER_ADMISSION = 32
        private val names = setOf(OWNER, LOCK, WAV, PCM, READY)
        private val processGeneration = UUID.randomUUID().toString()
        private val caches = mutableMapOf<File, CaptureCache>()

        @Synchronized
        fun forCache(directory: File, diagnostic: (String) -> Unit): CaptureCache =
            caches.getOrPut(directory.canonicalFile) { CaptureCache(directory, diagnostic = diagnostic) }

        private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        private fun regular(path: Path) = Files.isRegularFile(path, NOFOLLOW_LINKS)
        private fun directory(path: Path) = Files.isDirectory(path, NOFOLLOW_LINKS)
    }
}
