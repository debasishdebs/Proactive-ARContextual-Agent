package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    private fun ok(raw: String, kind: EndpointKind = EndpointKind.AZURE_OPENAI) = UrlNormalizer.normalize(raw, kind).getOrThrow()
    private fun err(raw: String, kind: EndpointKind = EndpointKind.AZURE_OPENAI) = UrlNormalizer.normalize(raw, kind).exceptionOrNull()!!.message!!

    @Test fun trimsLowercasesHostAndDropsTrailingSlash() =
        assertEquals("https://my-res.openai.azure.com/Path/V1", ok("  HTTPS://My-Res.OpenAI.Azure.com/Path/V1/  "))

    @Test fun addsSchemeByKind() {
        assertEquals("https://api.example.com", ok("api.example.com"))
        assertEquals("http://192.168.1.5:11434", ok("192.168.1.5:11434", EndpointKind.OLLAMA))
    }

    @Test fun dropsDefaultPort() = assertEquals("https://h.example", ok("https://h.example:443/"))

    @Test fun rejectsBadInput() {
        assertTrue(err("").contains("Enter"))
        assertTrue(err("https://bad host/x").contains("space"))
        assertTrue(err("ftp://h.example").contains("http"))
        assertTrue(err("https:///nohost").contains("host"))
    }

    @Test fun localModelPathIsNotAUrl() = assertEquals("/sdcard/m.gguf", ok("  /sdcard/m.gguf ", EndpointKind.LLAMACPP_LOCAL))

    @Test fun keysAreTrimmedAndMustNotContainSpaces() {
        assertEquals("abc123", UrlNormalizer.cleanKey("  abc123\n").getOrThrow())
        assertTrue(UrlNormalizer.cleanKey("abc 123").isFailure)
    }

    private fun keyErr(raw: String) = UrlNormalizer.cleanKey(raw).exceptionOrNull()!!.message!!

    @Test fun keyWithInvisibleOrSmartCharsIsRejected() {
        assertEquals("Key has invalid characters", keyErr("abc\u200B123"))
        assertEquals("Key has invalid characters", keyErr("\uFEFFabc123"))
        assertEquals("Key has invalid characters", keyErr("\u201Cabc123\u201D"))
    }

    private fun httpErr(raw: String, kind: EndpointKind, key: Boolean) =
        UrlNormalizer.normalize(raw, kind, hasKey = key).exceptionOrNull()?.message

    @Test fun azureRejectsHttp() = assertEquals("Azure needs https://", httpErr("http://x.example", EndpointKind.AZURE_OPENAI, true))

    @Test fun openAiCompatWithKeyRejectsRemoteHttp() {
        assertEquals("Use https:// when sending a key", httpErr("http://api.example.com", EndpointKind.OPENAI_COMPAT, true))
        assertEquals("Use https:// when sending a key", httpErr("http://8.8.8.8", EndpointKind.OPENAI_COMPAT, true))
        assertEquals("Use https:// when sending a key", httpErr("http://172.32.0.1", EndpointKind.OPENAI_COMPAT, true))
    }

    @Test fun openAiCompatHttpAllowedForLocalOrKeyless() {
        listOf("http://localhost:8080", "http://127.0.0.1:1", "http://[::1]:1", "http://10.0.2.2:8080", "http://192.168.1.5", "http://172.16.0.9", "http://10.1.2.3", "http://box.local")
            .forEach { assertEquals(it, null, httpErr(it, EndpointKind.OPENAI_COMPAT, true)) }
        assertEquals(null, httpErr("http://api.example.com", EndpointKind.OPENAI_COMPAT, false))
    }

    @Test fun ollamaAndLocalUnchanged() {
        assertEquals(null, httpErr("http://api.example.com", EndpointKind.OLLAMA, true))
        assertEquals(null, httpErr("/sdcard/m.gguf", EndpointKind.LLAMACPP_LOCAL, true))
    }
}
