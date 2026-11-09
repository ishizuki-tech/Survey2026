package com.negi.survey.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceWriterTest {
    @Test
    fun appendingAfterCloseDoesNotWaitForPublication() {
        val directory = Files.createTempDirectory("trace-sealing-").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val callers = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val open = File(directory, "events.open")
            val writer = TraceWriter(open, publish = {
                entered.countDown()
                release.await()
                it
            })
            writer.append("""{"event":"accepted"}""")
            val finalization = callers.submit<File?> { writer.finalizeToGzip() }
            org.junit.Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            callers.submit { writer.append("""{"event":"too late"}""") }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            release.countDown()
            assertEquals(open, finalization.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("""{"event":"accepted"}"""), open.readLines())
        } finally {
            release.countDown()
            callers.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedPreparationRetainsEventsUntilNextAppendOrFinalization() {
        for (appendAgain in listOf(false, true)) {
            val directory = Files.createTempDirectory("trace-prepare-").toFile()
            try {
                var attempts = 0
                val failures = mutableListOf<Exception>()
                val writer = TraceWriter(
                    File(directory, "events.open"),
                    prepare = { if (++attempts == 1) throw java.io.IOException("temporary storage failure") },
                    onFailure = failures::add,
                )
                val first = """{"event":"first"}"""
                val second = """{"event":"second"}"""
                writer.append(first)
                if (appendAgain) writer.append(second)

                val compressed = requireNotNull(writer.finalizeToGzip())
                val lines = GZIPInputStream(compressed.inputStream()).bufferedReader().use { it.readLines() }
                assertEquals(if (appendAgain) listOf(first, second) else listOf(first), lines)
                assertEquals(2, attempts)
                assertEquals(1, failures.size)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun finalizationDrainsOrderedNdjsonThenCompressesIt() {
        val directory = Files.createTempDirectory("survey-trace-").toFile()
        try {
            val open = File(directory, "x_survey.ndjson.open")
            val writer = TraceWriter(open)
            writer.append("{\"event\":\"one\"}")
            writer.append("{\"event\":\"two\"}")

            val compressed = requireNotNull(writer.finalizeToGzip())
            val lines = GZIPInputStream(compressed.inputStream()).bufferedReader().readLines()
            assertEquals(listOf("{\"event\":\"one\"}", "{\"event\":\"two\"}"), lines)
            assertEquals(false, open.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
