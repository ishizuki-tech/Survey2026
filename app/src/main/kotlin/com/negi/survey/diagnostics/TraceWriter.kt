package com.negi.survey.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** Serialized, best-effort NDJSON writer. Callers never wait for an individual event write. */
internal class TraceWriter(private val openFile: File) {
    val finalFileName: String
        get() = openFile.name.removeSuffix(".open").removeSuffix(".ndjson") + ".ndjson.gz"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SurveyTraceWriter").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    @Synchronized
    fun append(line: String) {
        if (closed) return
        runCatching {
            executor.execute {
                runCatching {
                    openFile.parentFile?.mkdirs()
                    BufferedWriter(OutputStreamWriter(FileOutputStream(openFile, true), StandardCharsets.UTF_8)).use {
                        it.write(line)
                        it.newLine()
                    }
                }
            }
        }
    }

    /** Runs only after queued writes, then atomically publishes the compressed trace where possible. */
    @Synchronized
    fun finalizeToGzip(): File? {
        if (closed) return null
        closed = true
        return runCatching {
            executor.submit<File?> {
                if (!openFile.exists()) return@submit null
                val finalFile = File(openFile.parentFile, finalFileName)
                val temporary = File(finalFile.parentFile, "${finalFile.name}.tmp")
                openFile.inputStream().use { input ->
                    GZIPOutputStream(FileOutputStream(temporary)).use { output -> input.copyTo(output) }
                }
                if (!temporary.renameTo(finalFile)) {
                    temporary.copyTo(finalFile, overwrite = true)
                    temporary.delete()
                }
                openFile.delete()
                finalFile
            }.get()
        }.getOrNull().also { executor.shutdown() }
    }
}
