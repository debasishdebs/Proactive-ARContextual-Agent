package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointRulesTest {
    private fun e(kind: EndpointKind = EndpointKind.OPENAI_COMPAT, name: String = "My server", url: String = "https://api.example.com",
                  models: List<String> = listOf("m"), caps: Set<Capability> = setOf(Capability.CHAT), apiVersion: String? = null) =
        Endpoint("e1", name, kind, url, apiVersion, models, caps, 1)
    private fun err(x: Endpoint, key: Boolean = false) = EndpointRules.clean(x, key).exceptionOrNull()?.message

    @Test fun nameIsOneLineTrimmedAndCappedOrFallsBackToTheHost() {
        assertEquals("My server", EndpointRules.clean(e(name = "  My\tserver \n"), false).getOrThrow().name)
        assertEquals(40, EndpointRules.clean(e(name = "x".repeat(90)), false).getOrThrow().name.length)
        assertEquals("api.example.com", EndpointRules.clean(e(name = "  "), false).getOrThrow().name)
    }

    @Test fun modelsAreTrimmedDedupedAndMustBeCsvSafe() {
        assertEquals(listOf("a", "b"), EndpointRules.clean(e(models = listOf(" a ", "", "b", "a")), false).getOrThrow().models)
        assertEquals("Model names can't contain commas or spaces", err(e(models = listOf("gpt 4"))))
        assertEquals("Model names can't contain commas or spaces", err(e(models = listOf("a,b"))))
    }

    @Test fun apiVersionOnlyForAzureWithADefault() {
        assertEquals("2024-10-21", EndpointRules.clean(e(kind = EndpointKind.AZURE_OPENAI, apiVersion = " "), true).getOrThrow().apiVersion)
        assertEquals("2025-01-01", EndpointRules.clean(e(kind = EndpointKind.AZURE_OPENAI, apiVersion = " 2025-01-01 "), true).getOrThrow().apiVersion)
        assertNull(EndpointRules.clean(e(apiVersion = "2024-10-21"), false).getOrThrow().apiVersion)
    }

    @Test fun keylessKindsRejectAKeyAndEveryEndpointNeedsACapability() {
        assertEquals("This endpoint type doesn't use a key", err(e(kind = EndpointKind.OLLAMA, url = "http://localhost:11434"), key = true))
        assertEquals("This endpoint type doesn't use a key", err(e(kind = EndpointKind.LLAMACPP_LOCAL, url = "/sdcard/m.gguf"), key = true))
        assertEquals("Pick at least one capability", err(e(caps = emptySet())))
    }

    @Test fun badIdsAreRejected() = assertEquals("Invalid endpoint id", EndpointRules.clean(e().copy(id = "a b"), false).exceptionOrNull()?.message)
}
