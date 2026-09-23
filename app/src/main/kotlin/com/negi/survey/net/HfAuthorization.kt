package com.negi.survey.net

import java.net.URL

/** Builds an Authorization value only for Hugging Face hosts. */
internal fun hfAuthorizationHeader(
    url: String,
    tokenProvider: () -> String?,
): String? {
    if (!isHfHost(url)) {
        return null
    }

    val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return "Bearer $token"
}

internal fun isHfHost(url: String): Boolean {
    val host = runCatching { URL(url).host.orEmpty() }.getOrDefault("")
    return host == "huggingface.co" || host.endsWith(".huggingface.co")
}
