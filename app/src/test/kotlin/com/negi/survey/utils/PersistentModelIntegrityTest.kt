package com.negi.survey.utils

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stateful provider fixture around the real persistence/validation adapter; no Android/OEM claims. */
class PersistentModelIntegrityTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private data class Row(val id: Long, var bytes: String, var pending: Boolean = false) {
        val uri = mockk<Uri>()
        var verifiedRead = false
    }
    private val rows = mutableListOf<Row>()
    // The adapter builds each ContentValues immediately before its resolver call.
    // Capture real put arguments; MockK constructor prototypes do not retain instance identity.
    private val pendingValues = mutableMapOf<String, Any>()
    private val events = mutableListOf<String>()
    private val reads = mutableListOf<Long>()
    private val inserted = mutableListOf<Map<String, Any>>()
    private val updates = mutableListOf<Map<String, Any>>()
    private val warnings = mutableListOf<Throwable>()
    private var created: Row? = null
    private var corruptWrite = false
    private var cancelRead = false
    private var failWrite = false
    private var failPublication = false
    private var failCleanup = false
    private var failInsert = false
    private val file get() = File(directory.root, "model.bin")

    @Before fun setup() {
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory.root
        every { context.contentResolver } returns resolver
        mockkObject(PersistentModelStore)
        every { PersistentModelStore.isSupported() } returns true
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } answers { warnings += thirdArg<Throwable>(); 0 }
        every { Log.i(any(), any()) } returns 0
        mockkConstructor(ContentValues::class)
        every { anyConstructed<ContentValues>().put(any(), any<String>()) } answers {
            pendingValues[firstArg()] = secondArg<String>()
        }
        every { anyConstructed<ContentValues>().put(any(), any<Int>()) } answers {
            pendingValues[firstArg()] = secondArg<Int>()
        }
        mockkStatic(ContentUris::class)
        every { ContentUris.withAppendedId(any(), any()) } answers { rows.single { it.id == secondArg<Long>() }.uri }
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val snapshot = rows.toList()
            var index = -1
            mockk<Cursor> {
                every { getColumnIndexOrThrow(any()) } answers {
                    when (firstArg<String>()) { "_id" -> 0; "_display_name" -> 1; "_size" -> 2; "is_pending" -> 3; else -> error("column") }
                }
                every { moveToNext() } answers { ++index < snapshot.size }
                every { getLong(0) } answers { snapshot[index].id }
                every { getLong(2) } answers { snapshot[index].bytes.length.toLong() }
                every { getString(1) } returns "model.bin"
                every { getInt(3) } answers { if (snapshot[index].pending) 1 else 0 }
                every { close() } just Runs
            }
        }
        every { resolver.insert(any(), any()) } answers {
            if (failInsert) throw IOException("insert failed")
            val actual = pendingValues.toMap().also { pendingValues.clear() }
            inserted += actual
            assertEquals("Insertion must remain pending", 1, actual["is_pending"])
            Row((rows.maxOfOrNull { it.id } ?: 0) + 1, "", pending = true).also {
                rows += it; created = it; events += "insert-pending"
            }.uri
        }
        every { resolver.openOutputStream(any()) } answers {
            val row = rows.single { it.uri == firstArg<Uri>() }
            assertTrue(row.pending)
            object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (failWrite) {
                        super.write(b, off, 1)
                        throw IOException("write failed")
                    }
                    super.write(b, off, len)
                }
                override fun close() {
                    row.bytes = if (corruptWrite) "wrong" else toString(Charsets.UTF_8.name())
                    events += "copy-closed"
                    assertTrue(row.pending)
                    super.close()
                }
            }
        }
        every { resolver.openInputStream(any()) } answers {
            val row = rows.single { it.uri == firstArg<Uri>() }
            reads += row.id
            if (row === created && cancelRead) throw CancellationException("canceled provider verification")
            if (row === created) assertTrue("Readback must precede publication", row.pending)
            object : ByteArrayInputStream(row.bytes.toByteArray()) {
                override fun close() {
                    if (pos == count && row.bytes == "valid") {
                        row.verifiedRead = true
                        events += "verified-read"
                    }
                    super.close()
                }
            }
        }
        every { resolver.update(any(), any(), any(), any()) } answers {
            val row = rows.single { it.uri == firstArg<Uri>() }
            val actual = pendingValues.toMap().also { pendingValues.clear() }
            updates += actual
            assertEquals("Publication must clear pending", 0, actual["is_pending"])
            assertTrue("Publication must follow complete valid readback", row.verifiedRead)
            assertTrue(row.pending)
            if (failPublication) throw IOException("publication failed")
            row.pending = false; events += "publish-completed"
            1
        }
        every { resolver.delete(any(), any(), any()) } answers {
            val row = rows.single { it.uri == firstArg<Uri>() }
            if (row === created && failCleanup) throw IOException("cleanup failed")
            if (row !== created) assertTrue("Fallback retired only after verified publication", created?.let { !it.pending && it.verifiedRead } == true)
            rows.remove(row); events += if (row === created) "cleanup-owned" else "retire-old"
            1
        }
    }
    @After fun cleanup() { unmockkAll() }

    private suspend fun persist() = PersistentModelStore.persistFromPrivate(context, "model.bin", file, identity = testIdentity("valid"))

    @Test fun invalidNewestAndPendingRowsCannotHideVerifiedFallback() = runBlocking {
        val valid = Row(101, "valid")
        val invalid = Row(102, "wrong")
        val pending = Row(103, "valid", true)
        rows += listOf(valid, invalid, pending)
        file.writeText("older")
        assertTrue(PersistentModelStore.reuseOrReplace(context, "model.bin", file, {}, {}, testIdentity("valid")))
        assertEquals(listOf(102L, 101L), reads)
        assertEquals("valid", file.readText())
        assertEquals(listOf(valid, invalid, pending), rows)
        verify(exactly = 0) { resolver.openInputStream(pending.uri) }
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test fun noValidPersistedCandidatePreservesInstalledFile() = runBlocking {
        rows += Row(1, "wrong")
        file.writeText("valid")
        assertFalse(PersistentModelStore.reuseOrReplace(context, "model.bin", file, {}, {}, testIdentity("valid")))
        assertEquals("valid", file.readText())
        assertFalse(directory.root.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun pendingBytesAreVerifiedBeforePublication() = runBlocking {
        rows += Row(1, "valid")
        file.writeText("valid")
        assertTrue(persist())
        assertEquals(1, inserted.single()["is_pending"])
        assertEquals(0, updates.single()["is_pending"])
        assertEquals(listOf("insert-pending", "copy-closed", "verified-read", "publish-completed", "retire-old"), events)
        assertEquals(listOf(created), rows)
        assertFalse(rows.single().pending)
        assertEquals("valid", rows.single().bytes)
    }

    @Test fun invalidSourceOrCorruptedPendingCopyNeverPublishesOrDeletesFallback() = runBlocking {
        val fallback = Row(1, "valid")
        rows += fallback
        for (corrupt in listOf(false, true)) {
            corruptWrite = corrupt
            file.writeText(if (corrupt) "valid" else "wrong")
            assertFalse(persist())
            assertTrue(updates.isEmpty())
            assertEquals(listOf(fallback), rows)
            assertTrue(events.last() == "cleanup-owned")
        }
    }

    @Test fun failedCopyRemovesOnlyOwnedPendingRow() = runBlocking {
        val fallback = Row(1, "valid").also { rows += it }
        file.writeText("valid")
        failWrite = true
        assertFalse(persist())
        assertEquals("v", created!!.bytes)
        assertEquals(listOf(fallback), rows)
        assertTrue(updates.isEmpty())
        assertTrue(events.last() == "cleanup-owned")
    }

    @Test fun failedPublicationPreservesPreviousCompletedModel() = runBlocking {
        val fallback = Row(1, "valid").also { rows += it }
        file.writeText("valid")
        failPublication = true
        assertFalse(persist())
        assertTrue(created!!.verifiedRead)
        assertTrue(created!!.pending)
        assertEquals(listOf(fallback), rows)
        assertFalse(events.contains("publish-completed"))
    }

    @Test fun canceledPendingVerificationCleansOnlyOwnedRow() = runBlocking {
        val fallback = Row(1, "valid").also { rows += it }
        file.writeText("valid")
        cancelRead = true
        assertTrue(runCatching { persist() }.exceptionOrNull() is CancellationException)
        assertTrue(updates.isEmpty())
        assertEquals(listOf(fallback), rows)
        assertEquals("valid", file.readText())
    }

    @Test fun failedCleanupRetainsPendingRowAndReportsFailureWithoutSuccess() = runBlocking {
        val fallback = Row(1, "valid").also { rows += it }
        file.writeText("wrong")
        failCleanup = true
        assertFalse(persist())
        assertEquals(listOf(fallback, created), rows)
        assertTrue(created!!.pending)
        assertTrue(updates.isEmpty())
        assertTrue(warnings.any { it.suppressed.any { suppressed -> suppressed.message == "cleanup failed" } })
    }

    @Test fun failedInsertionLeavesPreviousCompletedRowUnchanged() = runBlocking {
        val fallback = Row(1, "valid").also { rows += it }
        file.writeText("valid")
        failInsert = true
        assertFalse(persist())
        assertEquals(listOf(fallback), rows)
        assertNull(created)
        assertTrue(updates.isEmpty())
    }
}
