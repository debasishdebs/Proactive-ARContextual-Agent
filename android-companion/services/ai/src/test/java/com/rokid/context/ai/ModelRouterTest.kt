package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val calls = mutableListOf<String>()
    /** "id:text" / "id:vision" → exception to throw. */
    private val failures = mutableMapOf<String, Throwable>()
    private val providers = ProviderBuilder { e, model, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { calls += "${e.id}:text"; failures["${e.id}:text"]?.let { throw it }; return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { calls += "${e.id}:vision"; failures["${e.id}:vision"]?.let { throw it }; return "${e.id}/vision" }
        }
    }
    private val router = ModelRouter(bindings, endpoints, providers)
    @After fun close() = db.close()

    private suspend fun ep(id: String, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, EndpointKind.OLLAMA, "http://h:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA"))
    private fun http(code: Int) = IllegalStateException("Azure OpenAI request failed: $code {\"error\":\"x\"}")
    private suspend fun err(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull()!!

    @Test fun usesPrimaryBinding() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m1")).getOrThrow()
        assertEquals("a/m1", router.generate(Role.ANSWER, "q"))
    }

    @Test fun fallsBackWhenPrimaryFails() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m1", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        failures["a:text"] = http(500)
        assertEquals("b/m2", router.generate(Role.ANSWER, "q"))
    }

    @Test fun visionWhenCapableElseTextAndVisionFailureFallsBackToText() = runTest {
        ep("v", Capability.CHAT, Capability.VISION); ep("t", Capability.CHAT)
        bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        assertEquals("t/m", router.generate(Role.SUMMARIZE, "q", img))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        assertEquals("v/vision", router.generate(Role.VISION_EXTRACT, "q", img))
        failures["v:vision"] = http(400)
        assertEquals("v/m", router.generate(Role.VISION_EXTRACT, "q", img))
    }

    @Test fun framesUseSummarizeWhenNoVisionBinding() = runTest {
        ep("t", Capability.CHAT); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        assertEquals("t/m", router.generateFromFrames("q", img))
    }

    @Test fun frameOutageReachesSummarizeLocalFallbackOnce() = runTest {
        ep("az", Capability.CHAT, Capability.VISION); ep("loc", Capability.CHAT)
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "az", "gpt")).getOrThrow()
        bindings.set(RoleBinding(Role.SUMMARIZE, "az", "gpt", fallbackEndpointId = "loc", fallbackModel = "local")).getOrThrow()
        failures["az:vision"] = http(503); failures["az:text"] = http(503)
        assertEquals("loc/local", router.generateFromFrames("q", img))
        assertEquals(listOf("az:vision", "az:text", "loc:text"), calls) // Azure text is not retried a second time
    }

    @Test fun unboundRoleIsRetryable() = runTest {
        val e = err { router.generate(Role.NUDGE, "q") } as ModelCallException
        assertTrue(e.message!!.contains("No model is set for NUDGE")); assertTrue(e.retryable)
    }

    @Test fun serverErrorsAreRetryableAuthErrorsAreNot() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        failures["a:text"] = http(500)
        assertTrue((err { router.generate(Role.ANSWER, "q") } as ModelCallException).retryable)
        failures["a:text"] = http(401)
        assertFalse((err { router.generate(Role.ANSWER, "q") } as ModelCallException).retryable)
        failures["a:text"] = java.net.SocketTimeoutException("timeout")
        assertTrue((err { router.generate(Role.ANSWER, "q") } as ModelCallException).retryable)
    }

    @Test fun permanentPrimaryWithTransientFallbackIsRetryable() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b")).getOrThrow()
        failures["a:text"] = http(401); failures["b:text"] = http(502)
        assertTrue((err { router.generate(Role.ANSWER, "q") } as ModelCallException).retryable)
    }

    @Test fun missingKeyIsPermanentByTypeAndByRealProviderMessage() = runTest {
        // By type: Azure endpoint saved without a key → router's precondition check.
        endpoints.save(Endpoint("az", "Azure OpenAI", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT), 1), apiKey = null).getOrThrow()
        bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        val e = err { router.generate(Role.ANSWER, "q") } as ModelCallException
        assertFalse(e.retryable); assertTrue(e.message!!.contains("No API key saved"))
        // By message: the real legacy providers' own require() texts.
        val real = listOf(
            runCatching { com.rokid.context.model.provider.OpenAiCompatProvider("https://az.example", "", "gpt").generate("q") }.exceptionOrNull()!!,
            runCatching { com.rokid.context.model.provider.OpenAiCompatProvider("", "k", "gpt").generate("q") }.exceptionOrNull()!!,
            runCatching { com.rokid.context.model.provider.OllamaProvider("", "m").generate("q") }.exceptionOrNull()!!,
            runCatching { com.rokid.context.model.provider.LlamaCppNativeProvider("").generate("q") }.exceptionOrNull()!!,
        )
        real.forEach { assertTrue("permanent: ${it.message}", ProviderErrors.isPermanent(it)) }
    }

    @Test fun cancellationIsNeverWrapped() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b")).getOrThrow()
        failures["a:text"] = CancellationException("stopped")
        assertTrue(err { router.generate(Role.ANSWER, "q") } is CancellationException)
        assertEquals(listOf("a:text"), calls) // no fallback attempt after cancellation
    }

    @Test fun providerTextIsRedactedAndTruncatedInErrorsAndLogs() = runTest {
        val key = "sk-test-SECRET-1234567890"
        endpoints.save(Endpoint("az", "Azure OpenAI", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"),
            setOf(Capability.CHAT, Capability.VISION), 1), apiKey = key).getOrThrow()
        bindings.set(RoleBinding(Role.ANSWER, "az", "gpt")).getOrThrow()
        val body = "Azure OpenAI request failed: 401 Incorrect API key provided: $key. " + "x".repeat(500)
        failures["az:vision"] = IllegalStateException(body); failures["az:text"] = IllegalStateException(body)
        val e = err { router.generate(Role.ANSWER, "q", img) } as ModelCallException
        assertFalse(e.message!!.contains(key)); assertTrue(e.message!!.contains("request failed: 401"))
        assertTrue("length ${e.message!!.length}", e.message!!.length <= 260)
        assertFalse(e.retryable) // classified on the original throwable
        assertTrue(ShadowLog.getLogs().isNotEmpty())
        assertTrue(ShadowLog.getLogs().none { key in it.msg })
    }
}
