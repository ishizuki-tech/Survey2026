package com.negi.survey.net

import java.io.IOException
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #128: parallel uploads after a survey commit to the same branch, so a PUT can lose
 * the race with a 409 Conflict. These must be retried rather than failing for good.
 */
class GitHubUploadConflictRetryTest {

    @Test
    fun conflict_delay_grows_per_attempt_and_stays_in_the_upper_half_of_its_window() {
        val windows = listOf(1 to 400L, 2 to 800L, 3 to 1_600L, 4 to 3_200L, 5 to 6_400L)
        for ((attempt, cap) in windows) {
            repeat(200) { seed ->
                val delay = GitHubUploader.conflictRetryDelayMs(attempt, Random(seed))
                assertTrue("attempt $attempt delay $delay below ${cap / 2}", delay >= cap / 2)
                assertTrue("attempt $attempt delay $delay above $cap", delay <= cap)
            }
        }
    }

    @Test
    fun conflict_delay_is_capped() {
        repeat(200) { seed ->
            val delay = GitHubUploader.conflictRetryDelayMs(attempt = 20, random = Random(seed))
            assertTrue(delay in 4_000L..8_000L)
        }
    }

    @Test
    fun conflict_delays_spread_uploads_that_conflicted_together() {
        val delays = (0 until 15).map { GitHubUploader.conflictRetryDelayMs(1, Random(it)) }.toSet()
        assertTrue("expected spread delays, got $delays", delays.size > 5)
    }

    @Test
    fun conflict_409_is_retried_by_the_worker() {
        val error = IOException(
            "GitHub request failed (409): {\"message\":\"is at 6ed1327 but expected aa3d645\",\"status\":\"409\"}"
        )
        assertTrue(GitHubUploadWorker.shouldRetry(error))
    }

    @Test
    fun other_client_errors_still_fail_for_good() {
        for (code in listOf(400, 401, 403, 404, 422)) {
            val error = IOException("GitHub request failed ($code): {\"message\":\"nope\"}")
            assertFalse("HTTP $code must not be retried", GitHubUploadWorker.shouldRetry(error))
        }
    }

    @Test
    fun transient_errors_are_still_retried() {
        assertTrue(GitHubUploadWorker.shouldRetry(IOException("Transient HTTP 502: bad gateway")))
        assertTrue(GitHubUploadWorker.shouldRetry(IOException("GitHub request failed (503): down")))
        assertTrue(GitHubUploadWorker.shouldRetry(IOException("GitHub request failed (429): slow down")))
        assertFalse(GitHubUploadWorker.shouldRetry(IllegalStateException("bad credentials")))
    }
}
