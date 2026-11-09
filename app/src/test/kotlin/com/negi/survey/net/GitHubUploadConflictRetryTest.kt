package com.negi.survey.net

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GitHubUploadConflictRetryTest {
    private val success = GitHubUploader.UploadResult("url", "commit")

    @Test fun v2BinaryReadBackVerifiesAmbiguousAndSuccessfulWrites() = runBlocking {
        var reads = 0
        val recovered = GitHubUploader.createArtifactWithConflictRetry(
            "local", { if (reads++ == 0) null else "local" },
            { throw IOException("connection lost after create") }, verifyCreated = true,
        )
        assertEquals(GitHubUploader.UploadResult(null, null), recovered)

        reads = 0
        val mismatch = runCatching {
            GitHubUploader.createArtifactWithConflictRetry(
                "local", { if (reads++ == 0) null else "other" }, { success }, verifyCreated = true,
            )
        }.exceptionOrNull()
        assertTrue(mismatch is GitHubUploader.ArtifactConflictException)
    }

    @Test fun conflictsThenSuccess() = runBlocking {
        for (conflicts in listOf(1, 3)) {
            var calls = 0
            val waits = mutableListOf<Int>()
            val result = GitHubUploader.createArtifactWithConflictRetry("local", { null }, {
                if (calls++ < conflicts) throw GitHubUploader.HttpFailureException(409, "race")
                success
            }, { waits.add(it) })
            assertEquals(success, result)
            assertEquals(conflicts + 1, calls)
            assertEquals((1..conflicts).toList(), waits)
        }
    }

    @Test fun exhaustionRetainsConflict() = runBlocking {
        var calls = 0
        val error = runCatching {
            GitHubUploader.createArtifactWithConflictRetry("local", { null }, {
                calls++
                throw GitHubUploader.HttpFailureException(409, "race")
            }, {})
        }.exceptionOrNull()
        assertEquals(5, calls)
        assertTrue(error is GitHubUploader.ConflictRetriesExhaustedException)
    }

    @Test fun matchingRemoteAfterAmbiguousCreateDoesNotWriteAgain() = runBlocking {
        var reads = 0
        var writes = 0
        GitHubUploader.createArtifactWithConflictRetry("local", { if (reads++ == 0) null else "local" }, {
            writes++
            throw GitHubUploader.HttpFailureException(422, "already exists")
        }, {})
        assertEquals(1, writes)
    }

    @Test fun differentRemoteNeverOverwritten() = runBlocking {
        var writes = 0
        val error = runCatching {
            GitHubUploader.createArtifactWithConflictRetry("local", { "other" }, { writes++; success }, {})
        }.exceptionOrNull()
        assertTrue(error is GitHubUploader.ArtifactConflictException)
        assertEquals(0, writes)
    }

    @Test fun conflictDuringRaceDoesNotOverwriteOtherArtifact() = runBlocking {
        var reads = 0
        var writes = 0
        val error = runCatching {
            GitHubUploader.createArtifactWithConflictRetry("local", { if (reads++ == 0) null else "other" }, {
                writes++
                throw GitHubUploader.HttpFailureException(409, "race")
            }, {})
        }.exceptionOrNull()
        assertTrue(error is GitHubUploader.ArtifactConflictException)
        assertEquals(1, writes)
    }

    @Test fun concurrentDifferentArtifactsRecoverFromBranchRace() = runBlocking {
        val bothReady = CompletableDeferred<Unit>()
        var ready = 0
        var bAttempts = 0
        val aCommitted = CompletableDeferred<Unit>()
        val stored = mutableMapOf<String, String>()
        suspend fun upload(path: String, hash: String) = GitHubUploader.createArtifactWithConflictRetry(
            hash, { stored[path] }, {
                ready++
                if (ready == 2) bothReady.complete(Unit)
                if (ready <= 2) bothReady.await()
                if (path == "b.wav" && bAttempts++ == 0) {
                    throw GitHubUploader.HttpFailureException(409, "branch race")
                }
                stored[path] = hash
                if (path == "a.wav") aCommitted.complete(Unit)
                success
            }, { aCommitted.await() })
        val b = async { upload("b.wav", "b") }
        val a = async { upload("a.wav", "a") }
        a.await()
        b.await()
        assertEquals(mapOf("a.wav" to "a", "b.wav" to "b"), stored)
        assertEquals(2, bAttempts)
    }

    @Test fun cancellationStopsBackoff() = runBlocking {
        var writes = 0
        val error = runCatching {
            GitHubUploader.createArtifactWithConflictRetry("local", { null }, {
                writes++
                throw GitHubUploader.HttpFailureException(409, "race")
            }, { throw CancellationException("cancelled") })
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, writes)
    }

    @Test fun otherHttpErrorsAreNotRetriedByConflictLoop() = runBlocking {
        for (code in listOf(400, 403, 429, 500)) {
            var writes = 0
            val error = runCatching {
                GitHubUploader.createArtifactWithConflictRetry("local", { null }, {
                    writes++
                    throw GitHubUploader.HttpFailureException(code, "failure")
                }, {})
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(1, writes)
        }
    }
}
