/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: DeviceUploadTag.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** A filename-safe, human-visible device correlation label. */
@JvmInline
value class DeviceUploadTag(val value: String)

/** Android boundary for the device tag. The raw Android ID is never logged or returned. */
object DeviceUploadTagProvider {
    fun from(context: Context): DeviceUploadTag = DeviceUploadTagFormatter.format(
        model = Build.MODEL,
        androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
    )
}

/** Pure formatter for deterministic, filename-safe device upload tags. */
object DeviceUploadTagFormatter {
    private const val HASH_DOMAIN = "Survey2026.device-upload-tag.v1|"
    private const val CODE_LENGTH = 12
    private val unsafeChars = Regex("[^A-Za-z0-9._-]+")

    fun format(model: String?, androidId: String?): DeviceUploadTag {
        val safeModel = sanitizeModel(model)
        val normalizedId = androidId?.trim().orEmpty()
        val suffix = if (normalizedId.isBlank()) {
            "UNKNOWN"
        } else {
            sha256Hex(HASH_DOMAIN + normalizedId).take(CODE_LENGTH)
        }
        return DeviceUploadTag("${safeModel}_$suffix")
    }

    private fun sanitizeModel(model: String?): String =
        model.orEmpty()
            .trim()
            .replace(unsafeChars, "_")
            .trim('_')
            .take(80)
            .ifBlank { "unknown" }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02X".format(byte) }
}
