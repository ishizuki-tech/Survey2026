package com.negi.survey.utils

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArtifactOperationsTest {
    @get:Rule val directory = TemporaryFolder()
    private fun file(name: String = "model.bin") = File(directory.root, name)
    private fun input(text: String) = ByteArrayInputStream(text.toByteArray())

    /** First buffer is written to disk before the second read blocks, as in the original BB/BX race. */
    private class BlockedCopy : InputStream() {
        val blocked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var step = 0
        override fun read(): Int = error("Use buffered read")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return when (step++) {
                0 -> { buffer[offset] = 'A'.code.toByte(); 1 }
                1 -> {
                    blocked.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Test did not release blocked stream" }
                    buffer[offset] = 'X'.code.toByte()
                    1
                }
                else -> -1
            }
        }
    }

    @Test fun oneCharacterDestinationUsesUniqueStagingWithoutRenamingModel() = runBlocking {
        val destination = file("a")
        val lease = ModelArtifactOperations.begin(destination)
        lease.import({ input("model") }) { installed ->
            assertEquals(destination.canonicalFile, installed)
            assertEquals("a", installed.name)
        }
        assertEquals("model", destination.readText())
        assertEquals(listOf(destination), directory.root.listFiles()!!.toList())
    }

    @Test fun overlappingImportsCannotReproduceBXOrPublishStaleSelection() = runBlocking {
        val destination = file()
        val stream = BlockedCopy()
        var selected = "none"
        val old = ModelArtifactOperations.begin(destination)
        val oldJob = launch(Dispatchers.IO) {
            old.import({ stream }) { old.publish { selected = "old" } }
        }
        old.attach(oldJob)
        try {
            withTimeout(5000) { stream.blocked.await() }
            val oldStaging = directory.root.listFiles()!!.single { it.name.endsWith(".tmp") }
            assertEquals("A", oldStaging.readText())
            oldJob.cancel()
            val replacement = ModelArtifactOperations.begin(destination)
            replacement.import({ input("BB") }) { replacement.publish { selected = "new" } }
            assertEquals("BB", destination.readText())
            assertTrue(oldStaging.exists()) // New cleanup did not touch old staging.
        } finally {
            stream.release.countDown()
            oldJob.join()
        }
        assertEquals("BB", destination.readText())
        assertEquals("new", selected)
        assertEquals(listOf(destination), directory.root.listFiles()!!.toList())
    }

    @Test fun cancellationDuringBlockingReadPreservesInstalledModelAndCleansStaging() = runBlocking {
        val destination = file().apply { writeText("valid") }
        val stream = BlockedCopy()
        val lease = ModelArtifactOperations.begin(destination)
        var done = false
        val job = launch(Dispatchers.IO) { lease.import({ stream }) { done = true } }
        lease.attach(job)
        try {
            withTimeout(5000) { stream.blocked.await() }
            job.cancel()
        } finally {
            stream.release.countDown()
            job.join()
        }
        assertEquals("valid", destination.readText())
        assertFalse(done)
        assertEquals(1, directory.root.listFiles()!!.size)
    }

    @Test fun failedAtomicReplacementPreservesOldModelAndAllowsRecovery() = runBlocking {
        val destination = file().apply { writeText("valid") }
        val candidate = file("candidate.tmp").apply { writeText("new") }
        val lease = ModelArtifactOperations.begin(destination)
        lease.locked {
            val failure = runCatching {
                lease.promote(candidate) { from, to ->
                    throw AtomicMoveNotSupportedException(from.path, to.path, "injected")
                }
            }.exceptionOrNull()
            assertTrue(failure is AtomicMoveNotSupportedException)
            assertEquals("valid", destination.readText())
            assertEquals("new", candidate.readText())
            lease.promote(candidate)
        }
        assertEquals("new", destination.readText())
        assertFalse(candidate.exists())
    }

    @Test fun emptyOrPartiallyCopiedCandidateIsNeverInstalled() = runBlocking {
        val destination = file().apply { writeText("valid") }
        val lease = ModelArtifactOperations.begin(destination)
        val failure = runCatching {
            lease.import({ object : InputStream() {
                override fun read(): Int = throw IOException("injected read failure")
            } }) { fail("Must not install") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("valid", destination.readText())
        assertEquals(1, directory.root.listFiles()!!.size)
        assertTrue(runCatching { lease.import({ input("") }) {} }.exceptionOrNull() is IOException)
    }

    @Test fun persistenceSupersessionRemovesOnlyOwnedPendingRow() = runBlocking {
        val destination = file()
        val old = ModelArtifactOperations.begin(destination)
        var visible = "previous"
        val pending = mutableSetOf<String>()
        var replacement: ModelArtifactOperations.Lease? = null
        val operationFailure = runCatching { old.locked {
            val failure = runCatching {
                old.persist(
                    create = { "old-row".also { pending += it } },
                    write = { replacement = ModelArtifactOperations.begin(destination) },
                    commit = { visible = it },
                    cleanup = { pending -= it }
                )
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } }.exceptionOrNull()
        assertTrue(operationFailure is CancellationException)
        assertEquals("previous", visible)
        assertTrue(pending.isEmpty())
        replacement!!.locked {
            replacement.persist({ "new-row" }, {}, { visible = it }, {})
        }
        assertEquals("new-row", visible)
    }

    @Test fun supersessionDuringProviderCommitCleansRowBeforeNewPersistence() = runBlocking {
        val destination = file()
        val old = ModelArtifactOperations.begin(destination)
        var visible: String? = null
        val operationFailure = runCatching { old.locked {
            val failure = runCatching {
                old.persist({ "old" }, {}, {
                    visible = it
                    ModelArtifactOperations.begin(destination)
                }, { visible = null })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
        } }.exceptionOrNull()
        assertTrue(operationFailure is CancellationException)
        assertNull(visible)
    }

    @Test fun persistenceWriteAndCommitFailuresCleanOwnedRows() = runBlocking {
        for (failWrite in listOf(true, false)) {
            val lease = ModelArtifactOperations.begin(file())
            var cleaned = false
            lease.locked {
                assertTrue(runCatching {
                    lease.persist({ "pending" }, { if (failWrite) throw IOException("write") },
                        { throw IOException("commit") }, { cleaned = true })
                }.exceptionOrNull() is IOException)
            }
            assertTrue(cleaned)
        }
    }

    @Test fun oldProgressDoneAndErrorCannotReplaceCurrentState() {
        val destination = file()
        val old = ModelArtifactOperations.begin(destination)
        val current = ModelArtifactOperations.begin(destination)
        var state = "new Error"
        current.publish { state = "new Error" }
        for (late in listOf("old progress", "old Done", "old Error")) {
            assertTrue(runCatching { old.publish { state = late } }.exceptionOrNull() is CancellationException)
        }
        assertEquals("new Error", state)
    }

    @Test fun canonicalAliasesShareOwnership() {
        val old = ModelArtifactOperations.begin(file())
        val alias = File(directory.root, "other/../model.bin")
        ModelArtifactOperations.begin(alias)
        assertTrue(runCatching { old.publish {} }.exceptionOrNull() is CancellationException)
    }

    @Test fun unrelatedDestinationsProgressWhileOtherDestinationIsLocked() = runBlocking {
        val first = ModelArtifactOperations.begin(file("one"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch { first.locked { entered.complete(Unit); release.await() } }
        try {
            withTimeout(5000) { entered.await() }
            withTimeout(5000) {
                val second = ModelArtifactOperations.begin(file("two"))
                second.import({ input("two") }) {}
            }
            assertEquals("two", file("two").readText())
        } finally {
            release.complete(Unit)
            job.join()
        }
    }

    @Test fun sharedResumeStateRemainsLockedUntilCanceledWriterActuallyTerminates() = runBlocking {
        val destination = file()
        val old = ModelArtifactOperations.begin(destination)
        val blocked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val part = file("model.bin.part")
        val oldJob = launch(Dispatchers.IO) {
            old.locked {
                part.writeText("resume")
                blocked.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        old.attach(oldJob)
        withTimeout(5000) { blocked.await() }
        oldJob.cancel()
        val replacement = ModelArtifactOperations.begin(destination)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            replacement.locked {
                assertEquals("resume", part.readText())
                part.appendText("d")
                replacement.promote(part)
            }
        }
        try {
            assertFalse(waiting.isCompleted)
        } finally {
            release.countDown()
            oldJob.join()
        }
        withTimeout(5000) { waiting.await() }
        assertEquals("resumed", destination.readText())
    }
}
