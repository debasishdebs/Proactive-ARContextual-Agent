package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The roaming data saver at the router's choke point: a pure pre-check before the permit. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterDataSaverTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val providerCalls = mutableListOf<String>()
    private val permits = mutableListOf<CallClass>()
    private val failing = mutableSetOf<String>()
    private val callGate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { permits += callClass; return block() }
    }
    private val providers = ProviderBuilder { e, model, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { providerCalls += "${e.id}:text"; if (e.id in failing) error("boom"); return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { providerCalls += "${e.id}:vision"; return "${e.id}/vision" }
        }
    }

    /** Blocks per kind and reach; records what it was asked and what was skipped. */
    private class FakeGate(var blockPhoto: Boolean = false, var blockSmall: Boolean = false) : DataGate {
        val asked = mutableListOf<Pair<DataKind, EndpointReach>>()
        val skippedLog = mutableListOf<Triple<DataKind, Skipped, String>>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision {
            asked += kind to reach
            if (reach == EndpointReach.DEVICE) return DataDecision(true, "on_device")
            val block = if (kind == DataKind.PHOTO) blockPhoto else blockSmall
            return if (block) DataDecision(false, "wifi_only") else DataDecision.ALLOW
        }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skippedLog += Triple(kind, what, d.reason) }
        override fun costlyNow() = false
    }

    private val data = FakeGate()
    private val router = ModelRouter(bindings, endpoints, providers, callGate, data)
    @After fun close() = db.close()

    private suspend fun ep(id: String, kind: EndpointKind = EndpointKind.OLLAMA, url: String = "https://example.com:1", vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, kind, if (kind == EndpointKind.LLAMACPP_LOCAL) "model.gguf" else url, null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA", ref = "f1"))
    private suspend fun err(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull()!!

    @Test fun aBlockedPhotoCallThrowsBeforeThePermitAndTheProvider() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals("wifi_only", (e as DataSaverBlockedException).reason)
        assertTrue("no token spent", permits.isEmpty())
        assertTrue("nothing sent", providerCalls.isEmpty())
        assertEquals(listOf(Triple(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, "wifi_only")), data.skippedLog)
    }

    @Test fun aBlockedPhotoCallIsNotRetriedAsTextEvenWhenImagesAreOptional() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true
        // requireImages false: an ordinary vision failure would retry as text; a data-saver block must not
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue(providerCalls.isEmpty() && permits.isEmpty())
        assertEquals(1, data.skippedLog.size)
    }

    @Test fun theFramesChainDoesNotTryTheSummarizeEndpointAfterABlock() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); ep("t", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow(); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        data.blockPhoto = true
        val e = err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue("the text endpoint was not used as a way round the block", providerCalls.isEmpty())
    }

    @Test fun aBlockedPrimaryFallsBackOnlyToAnOnDeviceEndpoint() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION))
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "v", "m", fallbackEndpointId = "loc", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals("loc", r.endpoint.id)
        assertEquals(listOf(EndpointReach.REMOTE, EndpointReach.DEVICE), data.asked.map { it.second })
    }

    @Test fun aBlockedPrimaryWhoseOnDeviceFallbackFailsStillSurfacesAsTheBlock() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT)); ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "v", "m", fallbackEndpointId = "loc", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true; failing += "loc"
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue("not a generic failure that a caller would count as an attempt: $e", e is DataSaverBlockedException)
    }

    @Test fun aBlockedPrimaryWithARemoteFallbackIsBlockedToo() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); ep("b", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is DataSaverBlockedException)
        assertTrue(providerCalls.isEmpty())
    }

    @Test fun aBlockWithNoFallbackSurfacesAsTheBlockNotAGenericFailure() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.blockSmall = true
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue((e as ModelCallException).retryable)
        assertEquals(listOf(Triple(DataKind.SMALL, Skipped.BULK, "wifi_only")), data.skippedLog)
    }

    @Test fun textCallsAreAskedAsSmallAndPhotoCallsAsPhoto() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q") }
        assertEquals(listOf(DataKind.PHOTO, DataKind.SMALL), data.asked.map { it.first })
        assertEquals(listOf("v:vision", "v:text"), providerCalls)
    }

    @Test fun anEndpointThatCannotSendPhotosIsNotAPhotoCall() = runTest {
        ep("t", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        data.blockPhoto = true // photos would be blocked, but this endpoint only gets the text
        router.complete(Role.SUMMARIZE, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }
        assertEquals(listOf(DataKind.SMALL), data.asked.map { it.first })
    }

    @Test fun privateAddressesAreAskedAsLanAndTheHostIsNeverPassedOn() = runTest {
        ep("lan", url = "http://mybox.local:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        ep("ip", url = "https://192.168.1.20:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "ip", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        ep("cloud", url = "https://api.example.com", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "cloud", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(listOf(EndpointReach.LAN, EndpointReach.LAN, EndpointReach.REMOTE), data.asked.map { it.second })
    }

    @Test fun theDefaultRouterAllowsEverything() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        assertFalse(ModelRouter(bindings, endpoints, providers).complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.text.isEmpty())
    }
}
