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
}
