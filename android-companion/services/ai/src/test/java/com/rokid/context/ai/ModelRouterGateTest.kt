package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterGateTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val providerCalls = mutableListOf<String>()
    private val permits = mutableListOf<CallClass>()
    private val failures = mutableSetOf<String>()
    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { permits += callClass; return block() }
    }
    private val providers = ProviderBuilder { e, model, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { providerCalls += "${e.id}:text"; if ("${e.id}:text" in failures) error("boom"); return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { providerCalls += "${e.id}:vision"; if ("${e.id}:vision" in failures) error("boom"); return "${e.id}/vision" }
        }
    }
    private val router = ModelRouter(bindings, endpoints, providers, gate)
    @After fun close() = db.close()

    private suspend fun ep(id: String, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, EndpointKind.OLLAMA, "http://localhost:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA"))

    @Test fun everyProviderCallPassesThroughTheGate() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m1")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        router.completeOn("a", "m1", CallClass.USER) { ModelRequest("q") }
        assertEquals(providerCalls.size, permits.size)
        assertEquals(2, permits.size)
    }

    @Test fun fallbackRetryWithoutImagesIsAlsoGated() = runTest {
        ep("v", Capability.CHAT, Capability.VISION); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        failures += "v:vision"
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img) }
        assertEquals(listOf("v:vision", "v:text"), providerCalls)
        assertEquals(listOf(CallClass.EXTRACT, CallClass.EXTRACT), permits)
    }

    @Test fun fallbackKeepsCallersClass() = runTest {
        ep("a", Capability.CHAT); ep("b", Capability.CHAT)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m1", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        failures += "a:text"
        val r = router.complete(Role.ANSWER, CallClass.NUDGE) { ModelRequest("q") }
        assertEquals("b", r.endpoint.id)
        assertEquals(listOf(CallClass.NUDGE, CallClass.NUDGE), permits)
        // the frames chain (vision, then summarize) keeps it too
        permits.clear(); ep("v", Capability.CHAT, Capability.VISION)
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        failures += "v:text"; failures -= "a:text"
        bindings.set(RoleBinding(Role.SUMMARIZE, "a", "m1")).getOrThrow()
        router.completeFromFrames(CallClass.CAPTION_QUEUE) { ModelRequest("q") }
        assertEquals(setOf(CallClass.CAPTION_QUEUE), permits.toSet())
    }

    @Test fun routedReplyMetadataUnchanged() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m1", maxTokens = 77)).getOrThrow()
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals("a/m1", r.text); assertEquals("a", r.endpoint.id); assertEquals("m1", r.model); assertEquals(77, r.maxTokens)
    }

    @Test fun defaultGateChangesNothing() = runTest {
        ep("a", Capability.CHAT); bindings.set(RoleBinding(Role.ANSWER, "a", "m1")).getOrThrow()
        val plain = ModelRouter(bindings, endpoints, providers)
        @Suppress("DEPRECATION")
        assertEquals("a/m1", plain.generate(Role.ANSWER, "q"))
        assertEquals("a/m1", plain.generate(Role.ANSWER, CallClass.USER, "q"))
    }
}
