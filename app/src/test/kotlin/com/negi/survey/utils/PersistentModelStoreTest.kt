package com.negi.survey.utils

import android.net.Uri
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentModelStoreTest {

    @get:Rule val directory = TemporaryFolder()

    private fun row(id: Long, size: Long, pending: Boolean = false) =
        PersistentModelStore.PersistedEntry(id, mockk<Uri>(), "model.bin", size, pending)

    @Test fun interruptedCleanupRestoresNewestCompletedRowEvenWhenSmaller() {
        val old = row(10, 100)
        val replacement = row(11, 2)
        // Snapshot after publishing the replacement but before retiring the old row.
        for (rows in listOf(listOf(old, replacement), listOf(replacement, old))) {
            assertEquals(replacement, PersistentModelStore.bestOf(rows))
        }
    }

    @Test fun equalSizeReplacementIsIndependentOfProviderCursorOrder() {
        val old = row(20, 2)
        val replacement = row(21, 2)
        assertEquals(replacement, PersistentModelStore.bestOf(listOf(old, replacement)))
        assertEquals(replacement, PersistentModelStore.bestOf(listOf(replacement, old)))
    }

    @Test fun pendingOrEmptyReplacementPreservesLastCompletedNonemptyCopy() {
        val old = row(30, 100)
        val pending = row(31, 200, pending = true)
        val empty = row(32, 0)
        assertEquals(old, PersistentModelStore.bestOf(listOf(old, pending, empty)))
        assertNull(PersistentModelStore.bestOf(listOf(pending, empty)))
    }

    @Test fun failedReplacementWritePreservesPriorRecoveryCandidate() = runBlocking {
        val old = row(40, 100)
        val pending = row(41, 2, pending = true)
        val rows = mutableListOf(old)
        val lease = ModelArtifactOperations.begin(java.io.File(directory.root, "model.bin"))
        lease.locked {
            val failure = runCatching {
                lease.persist(
                    create = { pending.also { rows += it } },
                    write = { throw IOException("injected write failure") },
                    commit = { error("Failed write must not publish") },
                    cleanup = { rows -= it }
                )
            }.exceptionOrNull()
            assertTrue(failure is IOException)
        }
        assertEquals(listOf(old), rows)
        assertEquals(old, PersistentModelStore.bestOf(rows))
    }

    @Test fun failedOldRowDeletionDoesNotRollBackRecoverySelection() = runBlocking {
        val old = row(50, 100)
        val replacement = row(51, 2)
        val rows = mutableListOf(old)
        val lease = ModelArtifactOperations.begin(java.io.File(directory.root, "model.bin"))
        lease.locked {
            lease.persist(
                create = { replacement.copy(isPending = true).also { rows += it } },
                write = {},
                commit = { rows[rows.indexOf(it)] = replacement },
                cleanup = { rows -= it }
            )
            // Both a failed delete and process death before deletion leave this snapshot.
            assertEquals(listOf(old, replacement), rows)
            assertEquals(replacement, PersistentModelStore.bestOf(rows))
        }
    }

    @Test
    fun collisionVariant_matchesOnlyExactAndroidStyleNames() {
        val target = "gemma-3n-E4B-it-int4.litertlm"

        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant(target, target))
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1).litertlm", target))
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (8).litertlm", target))

        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4-old.litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (abc).litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1١).litertlm", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4 (1).bin", target))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("gemma-3n-E4B-it-int4-v2.litertlm", target))
    }

    @Test
    fun collisionVariant_preservesExactExtensionAndSupportsExtensionlessNames() {
        assertTrue(
            PersistentModelStore.isMediaStoreCollisionVariant(
                "gemma.model.v1 (2).litertlm",
                "gemma.model.v1.litertlm"
            )
        )
        assertTrue(PersistentModelStore.isMediaStoreCollisionVariant("model (3)", "model"))
        assertFalse(PersistentModelStore.isMediaStoreCollisionVariant("model (0)", "model"))
    }
}
