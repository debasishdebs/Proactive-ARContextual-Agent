package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SafeLogTest {
    private val body = "{\"error\":{\"message\":\"BODY-MARKER milk at 9am\"}}"

    /** Fix round 1 (T21 review I1): log lines reach Diagnostics › Logs and the export; never a message, only class, kind, HTTP code. */
    @Test fun aProviderBodyNeverReachesTheLogLine() {
        val raw = ProviderHttpException(400, "Azure OpenAI", body)
        assertEquals("ProviderHttpException(kind=REJECTED, http=400)", SafeLog.describe(raw))
        val wrapped = ModelCallException("Primary provider failed: Azure OpenAI request failed: 400 $body", retryable = false, cause = raw)
        assertEquals("ModelCallException(kind=REJECTED, retryable=false, http=400)", SafeLog.describe(wrapped))
        assertEquals("IOException(kind=TRANSIENT)", SafeLog.describe(IOException("BODY-MARKER reset")))
        listOf(raw, wrapped, IllegalStateException("BODY-MARKER"), RuntimeException(raw)).forEach { assertFalse(SafeLog.describe(it), "BODY-MARKER" in SafeLog.describe(it)) }
    }

    /** 2026-11-08: a 400 says which refusal it was (26 vision 400s on the real phone were otherwise indistinguishable), in fixed words only. */
    @Test fun aRefusalClassIsLoggedAsAFixedToken() {
        val filtered = ProviderHttpException(400, "Azure OpenAI", "{\"error\":{\"code\":\"content_filter\",\"message\":\"BODY-MARKER milk\"}}")
        assertEquals("ProviderHttpException(kind=REJECTED, http=400, reason=content_filter)", SafeLog.describe(filtered))
        val image = ModelCallException("x", retryable = false, cause = ProviderHttpException(400, "Azure OpenAI", "{\"error\":{\"message\":\"Invalid image data BODY-MARKER\"}}"))
        assertEquals("ModelCallException(kind=REJECTED, retryable=false, http=400, reason=image_rejected)", SafeLog.describe(image))
        assertFalse("BODY-MARKER" in SafeLog.describe(filtered))
    }

    /** ModelRouter.safe builds exception messages (which SafeLog no longer prints anyway): a provider body never enters them. */
    @Test fun routerSafeKeepsOnlyOurOwnFixedTexts() {
        val text = ModelRouter.safe(ProviderHttpException(401, "Azure OpenAI", "Incorrect API key provided: sk-1 $body"), "sk-1")
        assertEquals("ProviderHttpException(kind=AUTH, http=401)", text)
        assertEquals("No API key saved for Azure", ModelRouter.safe(ModelCallException("No API key saved for Azure", false, kind = FailureKind.NOT_SET)))
        assertTrue(ModelRouter.safe(ProviderConfigException("No address set")).contains("No address set"))
        assertFalse("BODY-MARKER" in ModelRouter.safe(IllegalStateException("BODY-MARKER")))
    }
}
