/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: EnsureModelDownloadedRealE2E.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class EnsureModelDownloadedRealE2E {

    private lateinit var appContext: Context
    private lateinit var authClient: OkHttpClient

    @Before
    fun setup() {
        appContext = InstrumentationRegistry.getInstrumentation().targetContext

        // English comment:
        // Shared OkHttp client for simple reachability checks.
        authClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    @After
    fun tearDown() {
        // English comment:
        // No global state to clean up here.
    }

    /**
     * Real network E2E:
     * - Requires a valid HF_TOKEN in BuildConfig.
     * - Forces a fresh download from the default Gemma LiteRT-LM URL.
     * - Verifies that the file exists and has non-zero length.
     * - Calls ensureModelDownloaded() again without forceFresh and checks that
     *   the same file is reused without changing its modification time.
     */
    @Test
    fun download_real_model_success_and_cached_on_second_call() = runBlocking<Unit> {
        // English comment:
        // Skip this test entirely when no Hugging Face token is configured.
        assumeTrue(
            "HF_TOKEN is blank. Set a valid token to run this E2E test.",
            BuildConfig.HF_TOKEN.isNotBlank()
        )

        val url = AppViewModel.DEFAULT_MODEL_URL
        assumeTrue("Target not reachable", canReach(url))

        // English comment:
        // Pre-clean the final file to make the test more deterministic.
        val fileName = suggestFileNameForTest(
            url = url,
            fallback = "model.litertlm"
        )
        val localFile = File(appContext.filesDir, fileName)
        localFile.delete()

        val vm = AppViewModel(
            modelUrl = url,
            timeoutMs = 10L * 60L * 1000L // 10 minutes hard timeout for test
        )

        // English comment:
        // Force a fresh download regardless of any previous cache.
        vm.ensureModelDownloaded(appContext, forceFresh = true)

        val done = withTimeout(600_000L) { // 10 minutes upper bound for slow networks
            vm.state.first { it is DlState.Done } as DlState.Done
        }

        Log.i("RealDlE2E", "Downloaded ${done.file} len=${done.file.length()}")

        assertTrue("Model file should exist", done.file.exists())
        assertTrue("Model file should not be empty", done.file.length() > 0L)

        // English comment:
        // Second call without forceFresh should short-circuit on the cached file.
        val mtimeBefore = done.file.lastModified()
        vm.ensureModelDownloaded(appContext, forceFresh = false)

        val stateAfter = vm.state.value
        assertTrue("State should remain Done after second call", stateAfter is DlState.Done)

        val doneAgain = stateAfter as DlState.Done
        assertEquals(
            "Second Done state should point to the same file path",
            done.file.absolutePath,
            doneAgain.file.absolutePath
        )

        val mtimeAfter = doneAgain.file.lastModified()
        assertEquals(
            "File modification time should be unchanged (no re-download)",
            mtimeBefore,
            mtimeAfter
        )
    }

    /**
     * Error path:
     * - Uses a clearly invalid URL that should fail quickly.
     * - Does not require HF_TOKEN.
     * - Verifies that DlState.Error is emitted with a non-blank message.
     */
    @Test
    fun invalid_url_should_emit_error_state() = runBlocking<Unit> {
        val badUrl = "https://127.0.0.1:9/does-not-exist-${System.currentTimeMillis()}"

        // English comment:
        // Short timeout to avoid hanging on unreachable host.
        val vm = AppViewModel(
            modelUrl = badUrl,
            timeoutMs = 5_000L
        )

        vm.ensureModelDownloaded(appContext, forceFresh = true)

        val err = withTimeout(60_000L) {
            vm.state.first { it is DlState.Error } as DlState.Error
        }

        Log.i("RealDlE2E", "Error as expected: ${err.message}")
        assertTrue(
            "Error message should not be blank",
            err.message.isNotBlank()
        )
    }

    /**
     * Simple reachability check with optional Authorization header.
     *
     * English comment:
     * - Tries HEAD first (fast and cheap).
     * - Falls back to GET if HEAD is rejected.
     * - Returns false on any exception.
     */
    private fun canReach(url: String): Boolean = try {
        val token = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }

        val headReq = Request.Builder()
            .url(url)
            .head()
            .apply {
                if (token != null) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        authClient.newCall(headReq).execute().use { r ->
            if (r.isSuccessful || (r.code in 200..399)) {
                return true
            }
        }

        val getReq = Request.Builder()
            .url(url)
            .get()
            .apply {
                val token2 = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }
                if (token2 != null) {
                    header("Authorization", "Bearer $token2")
                }
            }
            .build()

        authClient.newCall(getReq).execute().use { r ->
            r.isSuccessful || (r.code in 200..399)
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * Local copy of AppViewModel's filename derivation logic for test purposes.
     *
     * English comment:
     * - Mirrors the private suggestFileName implementation so that the test
     *   can locate the same on-disk file.
     */
    private fun suggestFileNameForTest(url: String, fallback: String): String {
        val raw = url.substringAfterLast('/').ifBlank { fallback }
        val stripped = raw.substringBefore('?')
        return if (stripped.isNotBlank()) stripped else raw
    }
}
