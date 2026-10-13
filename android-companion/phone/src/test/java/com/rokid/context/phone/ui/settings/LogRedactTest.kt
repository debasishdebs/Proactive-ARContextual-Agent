package com.rokid.context.phone.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fix round 1 (m4): `logcat --pid` also carries third-party SDK lines, so each credential shape is dropped. */
class LogRedactTest {
    private val kept = listOf(
        "I/RoleRunner: ANSWER v1 ok",
        "W/QueueDrain: job summarizer-6f1c2a3e-1b2c-4d5e-8f90-123456789abc RETRY attempt 2/5: ProviderHttpException(kind=TRANSIENT, http=503)",
        "I/Hub: secret handshake skipped; password prompt hidden", // the words alone, with no value, are fine
        "D/Task: sketch done",
    )

    private val dropped = mapOf(
        "bare sk-" to "D/Sdk: using sk-proj-AbCdEf123456789",
        "token=" to "D/Http: GET /v1?token=abc123",
        "access_token" to "D/Auth: access_token: abc.def",
        "secret=" to "D/Cfg: client_secret=xyz",
        "password=" to "D/Cfg: password = hunter2",
        "Ocp-Apim-Subscription-Key" to "D/OkHttp: Ocp-Apim-Subscription-Key: 0123",
        "?key=" to "D/OkHttp: --> GET https://x.example/v1/models?key=AIzaSyA",
        "&key=" to "D/OkHttp: --> GET https://x.example/v1/models?alt=json&key=AIzaSyA",
        "api.key" to "D/Cfg: api.key loaded",
        "JWT" to "D/Auth: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig",
        "base64 run" to "D/Sdk: blob QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9w",
    )

    @Test fun eachCredentialShapeIsDroppedAndOrdinaryLinesStay() {
        assertEquals(kept, LogReader.redact(kept))
        dropped.forEach { (shape, line) -> assertEquals(shape, emptyList<String>(), LogReader.redact(listOf(line))) }
    }

    // ---- Phase 8 D3 ----

    @Test fun jniDescriptorLinesAreKept() {
        val lines = listOf(
            "W/art: Rejecting re-init on previously-failed class Lcom/rokid/cxr/client/extend/listeners/SomeCallbackInterface;",
            "I/art: Lcom/rokid/cxr/client/extend/listeners/SomeCallbackInterface2;",
        )
        assertEquals(lines, LogReader.redact(lines))
    }

    /** (pin) Real keys still go: Base64 of random bytes, with and without `/`. */
    @Test fun aRealBase64KeyLineIsDropped() {
        val rnd = java.util.Random(42)
        val keys = (1..200).map { java.util.Base64.getEncoder().encodeToString(ByteArray(32).also(rnd::nextBytes)) } +
            "Zm9vYmFy/QmF6cXV4/aGVsbG8xMjM0NTY3ODkwYWJjZA=="
        keys.forEach { k -> assertEquals(k, emptyList<String>(), LogReader.redact(listOf("D/Sdk: auth $k"))) }
    }

    @Test fun isLikelyBase64SecretNeedsMixedClassesAndNoIdentifierPath() {
        assertEquals(false, LogReader.isLikelyBase64Secret("comrokidcxrclientextendlistenersSomeCallback"))
        assertEquals(false, LogReader.isLikelyBase64Secret("com/rokid/cxr/client/v2/listeners/SomeCallback2"))
        assertEquals(true, LogReader.isLikelyBase64Secret("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9w"))
    }
}
