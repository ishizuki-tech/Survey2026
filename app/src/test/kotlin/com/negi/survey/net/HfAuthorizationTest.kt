package com.negi.survey.net

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class HfAuthorizationTest {

    @Test
    fun hugging_face_url_receives_bearer_token() {
        assertEquals(
            "Bearer token-fixture",
            hfAuthorizationHeader(
                "https://huggingface.co/google/example/resolve/main/model.bin",
            ) { "token-fixture" },
        )
    }

    @Test
    fun non_hugging_face_url_does_not_request_token() {
        var providerCalls = 0

        assertNull(
            hfAuthorizationHeader("https://example.com/model.bin") {
                providerCalls += 1
                "token-fixture"
            },
        )
        assertEquals(0, providerCalls)
    }
}
