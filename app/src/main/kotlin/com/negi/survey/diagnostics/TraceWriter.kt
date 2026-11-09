package com.negi.survey.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** Serialized, best-effort NDJSON writer. Callers never wait for an individual event write. */
internal class TraceWriter(
    private val openFile: File,
    val finalFileName: String = openFile.name.removeSuffix(".open").removeSuffix(".ndjson") + ".ndjson.gz",
    private val prepare: () -> Unit = {},
    private val onFailure: (Exception) -> Unit = {},
    private val publish: ((File) -> File?)? = null,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SurveyTraceWriter").apply { isDaemon = true }
    }
    private var closed = false
    // Only the writer thread accesses these. Bound the backlog if storage stays unavailable.
    private var prepared = false
    private val pending = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        if (closed) return
        executor.execute {
            if (pending.size == 64) {
                pending.removeFirst()
                onFailure(java.io.IOException("Trace preparation backlog is full."))
            }
            pending.addLast(line)
            try {
                drain()
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }

    private fun drain() {
        if (!prepared) {
            prepare()
            prepared = true
        }
        if (pending.isEmpty()) return
        Files.createDirectories(openFile.parentFile!!.toPath())
        FileOutputStream(openFile, true).bufferedWriter(Charsets.UTF_8).use { output ->
            while (pending.isNotEmpty()) {
                output.write(pending.removeFirst())
                output.newLine()
            }
        }
    }

    /** Runs after queued appends and retries preparation once if there is a pending event. */
    fun finalizeToGzip(): File? {
        val result = synchronized(this) {
            if (closed) return null
            closed = true
            executor.submit<File?> {
                try {
                    if (pending.isNotEmpty()) {
                        try {
                            drain()
                        } catch (error: Exception) {
                            onFailure(error)
                        }
                    }
                    if (!openFile.exists()) return@submit null
                    if (publish != null) return@submit publish.invoke(openFile)
                    val finalFile = File(openFile.parentFile, finalFileName)
                    val temporary = File(finalFile.parentFile, "${finalFile.name}.tmp")
                    openFile.inputStream().use { input ->
                        GZIPOutputStream(FileOutputStream(temporary)).use { output -> input.copyTo(output) }
                    }
                    Files.move(temporary.toPath(), finalFile.toPath(), ATOMIC_MOVE)
                    openFile.delete()
                    finalFile
                } catch (error: Exception) {
                    onFailure(error)
                    null
                }
            }.also { executor.shutdown() }
        }
        return try {
            result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}
