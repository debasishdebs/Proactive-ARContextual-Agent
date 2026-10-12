package com.rokid.context.ai.embed

import com.rokid.context.ai.EndpointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EmbeddingSpecTest {
    private val s = EmbeddingSpec("ep1", EndpointKind.OLLAMA, "http://box.local:11434", null, "nomic-embed-text", "sha256:abc", null, 768, true,
        "search_document: ", "search_query: ", 2_000)

    @Test fun fingerprintIgnoresTheEndpointIdButNotTheConfig() {
        assertEquals(s.fingerprint, s.copy(endpointId = "renamed").fingerprint)
        listOf(s.copy(model = "bge-m3"), s.copy(revision = "sha256:def"), s.copy(dim = 1024), s.copy(queryPrefix = ""), s.copy(l2 = false),
            s.copy(baseUrl = "http://other.local:11434"), s.copy(maxInputChars = 512), s.copy(dimensions = 256))
            .forEach { assertNotEquals(it.toString(), s.fingerprint, it.fingerprint) }
        assertEquals(64, s.fingerprint.length)
    }

    @Test fun configRoundTrips() {
        assertEquals(s, EmbeddingSpec.fromConfig(s.configJson()))
        assertEquals(null, EmbeddingSpec.fromConfig("{not json"))
    }

    @Test fun prefixRulesForKnownFamilies() {
        assertEquals("search_document: " to "search_query: ", PrefixRules.forModel("nomic-embed-text:latest"))
        assertEquals("passage: " to "query: ", PrefixRules.forModel("intfloat/multilingual-e5-large"))
        assertEquals("" to "", PrefixRules.forModel("bge-m3"))
    }
}
