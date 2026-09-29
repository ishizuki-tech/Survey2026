package com.negi.survey.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceWriterTest {
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
