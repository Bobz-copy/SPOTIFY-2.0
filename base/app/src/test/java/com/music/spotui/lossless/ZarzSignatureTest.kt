package com.music.spotui.lossless

import org.junit.Assert.assertEquals
import org.junit.Test

/** ZARZ-HMAC-V1, checked against an independent implementation of the same protocol. */
class ZarzSignatureTest {
    @Test fun signsLikeTheExtensionRuntime() {
        val h = ZarzSession.signHeaders(
            sessionId = "sess_123", secret = "s3cr3t", appVersion = "tidal-web@1.2.5",
            method = "POST", path = "/v2/dl/tid", body = """{"id":"1"}""".toByteArray(),
            nowMs = 1_760_000_000_123, nonce = "abcdef012345",
        )
        assertEquals("2025-10-09T08:53:20.123Z", h["X-Zarz-Timestamp"])
        assertEquals("5811967f540d300d249ab30ae681359a7815fdb5d3dc71a94be1d491006a6b27", h["X-Zarz-Body-SHA256"])
        assertEquals("8EVagVFjx3zbtgAmLx-ZDgxj579Dc4nuoYlfYaOlJNw", h["X-Zarz-Signature"])
        assertEquals("tidal-web@1.2.5", h["X-Zarz-App-Version"])
        assertEquals("extension", h["X-Zarz-Platform"])
    }
}
