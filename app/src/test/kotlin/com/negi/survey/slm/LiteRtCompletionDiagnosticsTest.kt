/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: LiteRtCompletionDiagnosticsTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.slm

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtCompletionDiagnosticsTest {

    @Test
    fun completion_diagnostics_are_content_free_and_identify_normal_on_done() {
        val message = formatCompletionDiagnostics(
            runId = 17L,
            engineTokenCapacity = 512,
            prefillTokens = 481,
            decodeTokens = 9,
            kvTokens = 490,
            outputChars = 31,
            callbackCount = 10,
            terminal = InferenceTerminalCategory.NORMAL_ON_DONE,
        )

        assertEquals(
            "LiteRT completion: runId=17 terminal=normal_onDone phase=unavailable " +
                    "engineTokenCapacity=512 prefillTokens=481 decodeTokens=9 kvTokens=490 " +
                    "outputChars=31 callbacks=10",
            message,
        )
    }

    @Test
    fun completion_diagnostics_preserve_unavailable_token_markers_for_watchdog() {
        val message = formatCompletionDiagnostics(
            runId = 18L,
            engineTokenCapacity = 512,
            prefillTokens = -1,
            decodeTokens = -1,
            kvTokens = -1,
            outputChars = 0,
            callbackCount = 0,
            terminal = InferenceTerminalCategory.WATCHDOG_TIMEOUT,
        )

        assertEquals(true, message.contains("terminal=watchdog_timeout"))
        assertEquals(true, message.contains("prefillTokens=-1 decodeTokens=-1 kvTokens=-1"))
    }
}
