/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploadWorkerTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.net

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_BRANCH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_NAME
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_PATH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_OWNER
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_PATH_PREFIX
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_REPO
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_TOKEN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.text.Charsets

/**
 * Instrumentation tests for [GitHubUploadWorker] failure behavior.
 *
 * Covered cases:
 *  - Missing or blank token must yield [ListenableWorker.Result.Failure].
 *  - Exceeding the retry limit must also surface as [ListenableWorker.Result.Failure].
 *
 * Notes:
 *  - The "missing token" test fails during input validation and never reaches
 *    the network or GitHubUploader.
 *  - The "retry limit" test may attempt an HTTP request, but always uses an
 *    invalid token and is expected to fail quickly. It primarily verifies how
 *    the worker surfaces failure once the retry limit is reached.
 */
@RunWith(AndroidJUnit4::class)
class GitHubUploadWorkerTest {

    /**
     * When the token is missing or blank, the worker must return Failure
     * immediately based on input validation.
     */
    @Test
    fun missingToken_returnsFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = createPayloadFile(context.filesDir, "worker_missing_token.json")

        try {
            val input = workDataOf(
                KEY_OWNER to "owner",
                KEY_REPO to "repo",
                KEY_BRANCH to "main",
                KEY_TOKEN to "",          // Blank token must trigger failure.
                KEY_PATH_PREFIX to "",
                KEY_FILE_PATH to payload.absolutePath,
                KEY_FILE_NAME to payload.name
            )

            val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
                .setInputData(input)
                .build()

            val result = worker.doWork()

            assertTrue(
                "Expected Failure when token is blank",
                result is ListenableWorker.Result.Failure
            )
        } finally {
            payload.delete()
        }
    }

    /**
     * When the worker has already reached the retry limit, it must surface Failure
     * instead of retrying again.
     *
     * Note:
     *  - runAttemptCount is set to match the worker-side max-attempts value
     *    (currently 5). Keep this in sync with GitHubUploadWorker's MAX_ATTEMPTS.
     */
    @Test
    fun reachingRetryLimit_surfacesFailure() = runBlocking {
        // Worker implementation relies on Android Q+ foreground service types.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@runBlocking
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = createPayloadFile(context.filesDir, "worker_retry_limit.json")

        try {
            val input = workDataOf(
                KEY_OWNER to "owner",
                KEY_REPO to "repo",
                KEY_BRANCH to "main",
                KEY_TOKEN to "fake-token", // Invalid token forces the error path.
                KEY_PATH_PREFIX to "",
                KEY_FILE_PATH to payload.absolutePath,
                KEY_FILE_NAME to payload.name
            )

            val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
                .setInputData(input)
                // Must be equal to the worker's MAX_ATTEMPTS (currently 5).
                .setRunAttemptCount(5)
                .build()

            val result = worker.doWork()

            assertTrue(
                "Expected Failure when reaching retry limit",
                result is ListenableWorker.Result.Failure
            )
        } finally {
            payload.delete()
        }
    }

    /**
     * Creates a small JSON payload file under [baseDir] for the worker to upload.
     */
    private fun createPayloadFile(baseDir: File, name: String): File {
        val file = File(baseDir, name)
        file.parentFile?.mkdirs()
        file.writeText("{}", Charsets.UTF_8)
        return file
    }
}
