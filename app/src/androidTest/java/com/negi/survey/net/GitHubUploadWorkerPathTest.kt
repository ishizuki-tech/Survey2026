/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploadWorkerPathTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for the private [GitHubUploadWorker.buildDatedRemotePath] helper.
 *
 * Verifies that:
 *  - Empty prefix produces "yyyy-MM-dd/fileName".
 *  - Non-empty prefix is normalized and inserted before the date segment.
 *  - Leading slashes in file name are removed.
 *
 * Note:
 *  The actual upload path is built by [GitHubUploader.buildPath].
 *  This test only validates the worker's "remotePath" metadata helper
 *  remains consistent with that convention.
 */
@RunWith(AndroidJUnit4::class)
class GitHubUploadWorkerPathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * When prefix is empty, the remote path should be "yyyy-MM-dd/survey.json".
     */
    @Test
    fun buildDatedRemotePath_withEmptyPrefix_hasDateAndFileName() {
        val path = invokeBuildDatedRemotePath(prefix = "", fileName = "survey.json")
        // Example: 2025-11-17/survey.json
        val regex = Regex("""\d{4}-\d{2}-\d{2}/survey\.json""")
        assertTrue("Unexpected path: $path", regex.matches(path))
    }

    /**
     * Non-empty prefix should be normalized with a single slash, and
     * date segment inserted between prefix and file name:
     *
     *  "exports" / yyyy-MM-dd / "survey.json"
     */
    @Test
    fun buildDatedRemotePath_withPrefix_normalizesAndAddsDate() {
        val p1 = invokeBuildDatedRemotePath(prefix = "exports", fileName = "survey.json")
        val p2 = invokeBuildDatedRemotePath(prefix = "exports/", fileName = "survey.json")

        val regex = Regex("""exports/\d{4}-\d{2}-\d{2}/survey\.json""")
        assertTrue("Unexpected path for p1: $p1", regex.matches(p1))
        assertTrue("p1 and p2 should be identical", p1 == p2)
    }

    /**
     * Leading slash in the file name must be removed to avoid double
     * separators:
     *
     *  prefix="exports", fileName="/survey.json"
     *   → "exports/yyyy-MM-dd/survey.json"
     */
    @Test
    fun buildDatedRemotePath_stripsLeadingSlashFromFileName() {
        val path = invokeBuildDatedRemotePath(prefix = "exports", fileName = "/survey.json")
        val regex = Regex("""exports/\d{4}-\d{2}-\d{2}/survey\.json""")
        assertTrue("Unexpected path: $path", regex.matches(path))
    }

    /**
     * Uses reflection to call the private [GitHubUploadWorker.buildDatedRemotePath]
     * method on a test worker instance.
     */
    private fun invokeBuildDatedRemotePath(prefix: String, fileName: String): String {
        val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
            .setInputData(androidx.work.workDataOf())
            .build()

        val method = GitHubUploadWorker::class.java.declaredMethods.firstOrNull { m ->
            m.name == "buildDatedRemotePath" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java
        } ?: error(
            "GitHubUploadWorker.buildDatedRemotePath(String, String) not found. " +
                    "Check that the method name and signature have not changed."
        )

        method.isAccessible = true
        val result = method.invoke(worker, prefix, fileName)
        require(result is String) {
            "buildDatedRemotePath should return String, but returned ${result?.javaClass?.name}"
        }
        return result
    }
}
