/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadRescheduleReceiverIntentClassificationTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRescheduleReceiverIntentClassificationTest {

    @Test fun `MY_PACKAGE_REPLACED is a relevant action`() {
        assertTrue(UploadRescheduleReceiver.isRelevantAction("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test fun `BOOT_COMPLETED is a relevant action`() {
        assertTrue(UploadRescheduleReceiver.isRelevantAction("android.intent.action.BOOT_COMPLETED"))
    }

    @Test fun `USER_UNLOCKED is a relevant action`() {
        assertTrue(UploadRescheduleReceiver.isRelevantAction("android.intent.action.USER_UNLOCKED"))
    }

    @Test fun `LOCKED_BOOT_COMPLETED is a relevant action`() {
        assertTrue(UploadRescheduleReceiver.isRelevantAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
    }

    @Test fun `ACTION_SEND is not a relevant action`() {
        assertFalse(UploadRescheduleReceiver.isRelevantAction("android.intent.action.SEND"))
    }

    @Test fun `empty string is not a relevant action`() {
        assertFalse(UploadRescheduleReceiver.isRelevantAction(""))
    }
}
