package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.embed.EmbeddingClient
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.core.net.ModelPrivacy
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

/**
 * Model privacy "Offline only" at the router's choke point (slice 1.4.3). A fake provider counts every call that would reach a wire; under OFFLINE_ONLY a REMOTE endpoint must never be built, called or
 * reach the call permit, whichever entry point, retry or fallback led to it. Only this phone and the user's own network (LAN) may be asked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterOfflineOnlyTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val built = mutableListOf<String>()
    private val providerCalls = mutableListOf<String>()
    private val permits = mutableListOf<CallClass>()
    private val callGate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { permits += callClass; return block() }
    }
    private val providers = ProviderBuilder { e, model, _ ->
        built += e.id
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { providerCalls += "${e.id}:text"; return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { providerCalls += "${e.id}:vision"; return "${e.id}/vision" }
        }
    }
    private var mode: () -> ModelPrivacy = { ModelPrivacy.OFFLINE_ONLY }
    private val router = ModelRouter(bindings, endpoints, providers, callGate, privacy = { mode() })
    @After fun close() = db.close()

    private val remote = "https://example.com:1"
    private val lan = "http://box.local:11434"
    private suspend fun ep(id: String, kind: EndpointKind = EndpointKind.OLLAMA, url: String = remote, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, kind, if (kind == EndpointKind.LLAMACPP_LOCAL) "model.gguf" else url, null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val chat = arrayOf(Capability.CHAT)
    private val vision = arrayOf(Capability.CHAT, Capability.VISION)
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA", ref = "f1"))
    private suspend fun err(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() ?: error("expected a failure")
    private fun assertNothingReachedAWire() { assertTrue("built: $built", built.isEmpty()); assertTrue("calls: $providerCalls", providerCalls.isEmpty()); assertTrue("permits: $permits", permits.isEmpty()) }

    // ---- refusal on every entry point ----

    @Test fun aRemotePrimaryIsRefusedWithTheTypedBlockAndNothingIsBuiltOrCalled() = runTest {
        ep("r", caps = chat); bindings.set(RoleBinding(Role.ANSWER, "r", "m")).getOrThrow()
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue(e.toString(), e is OfflineOnlyRefusedException)
        assertTrue("it parks like a data-saver block: no attempt used", e is DataSaverBlockedException && e.retryable)
        assertEquals("offline_only", (e as DataSaverBlockedException).reason)
        assertNothingReachedAWire()
    }

    @Test fun aRemotePhotoCallIsRefusedAndNeverRetriedAsText() = runTest {
        ep("v", caps = vision); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
    }

    @Test fun aRemoteFallbackIsRefusedToo() = runTest {
        ep("a", caps = chat); ep("b", caps = chat)
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
    }

    @Test fun aRemotePrimaryFallsBackOnlyToAnOnDeviceOrLanEndpoint() = runTest {
        ep("r", caps = chat); ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = chat); ep("home", url = lan, caps = chat)
        bindings.set(RoleBinding(Role.ANSWER, "r", "m", fallbackEndpointId = "loc", fallbackModel = "m2")).getOrThrow()
        assertEquals("loc", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.endpoint.id)
        bindings.set(RoleBinding(Role.ANSWER, "r", "m", fallbackEndpointId = "home", fallbackModel = "m2")).getOrThrow()
        assertEquals("home", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.endpoint.id)
        assertEquals("the remote endpoint was never built", listOf("loc", "home"), built)
    }

    @Test fun anOnDeviceOrLanPrimaryWorks() = runTest {
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = chat); ep("home", url = lan, caps = vision)
        bindings.set(RoleBinding(Role.ANSWER, "loc", "m")).getOrThrow()
        assertEquals("loc", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.endpoint.id)
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "home", "m")).getOrThrow()
        val r = router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) }
        assertEquals("home", r.endpoint.id); assertTrue(r.imagesDelivered)
    }

    @Test fun theFramesChainRefusesARemoteVisionEndpointAndDoesNotTryASummarizeEndpointAsAWayRound() = runTest {
        ep("v", caps = vision); ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = chat)
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow(); bindings.set(RoleBinding(Role.SUMMARIZE, "loc", "m")).getOrThrow()
        assertTrue(err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } } is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
    }

    @Test fun theFramesChainRefusesARemoteSummarizeEndpointToo() = runTest {
        ep("s", caps = chat)
        bindings.set(RoleBinding(Role.SUMMARIZE, "s", "m")).getOrThrow() // no VISION_EXTRACT binding: the call goes straight to SUMMARIZE
        assertTrue(err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q") } } is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
    }

    @Test fun theTestBedAndTheLegacyGenerateHelpersAreRefusedToo() = runTest {
        ep("r", caps = chat); bindings.set(RoleBinding(Role.ANSWER, "r", "m")).getOrThrow(); ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = chat)
        assertTrue(err { router.completeOn("r", "m", CallClass.OTHER) { ModelRequest("q") } } is OfflineOnlyRefusedException)
        assertTrue(err { router.generate(Role.ANSWER, CallClass.USER, "q") } is OfflineOnlyRefusedException)
        bindings.set(RoleBinding(Role.SUMMARIZE, "r", "m")).getOrThrow()
        assertTrue(err { router.generateFromFrames(CallClass.EXTRACT, "q", img, AllLive) } is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
        assertEquals("loc/m", router.completeOn("loc", "m", CallClass.OTHER) { ModelRequest("q") }.text) // the phone itself still answers
    }

    @Test fun anUnreadableOrLookAlikeAddressCountsAsRemote() {
        fun reach(url: String, kind: EndpointKind = EndpointKind.OLLAMA) = EndpointReaches.of(Endpoint("x", "x", kind, url, null, listOf("m"), setOf(Capability.CHAT), 1))
        assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, reach("https://[bad"))
        assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, reach("not a url"))
        assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, reach(""))
        assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, reach("http://192.168.1.5.evil.example.com:11434"))
        assertEquals(com.rokid.context.core.net.EndpointReach.REMOTE, reach("http://[2001:db8::1]:11434"))
        assertEquals(com.rokid.context.core.net.EndpointReach.LAN, reach("http://192.168.1.5:11434"))
        assertEquals(com.rokid.context.core.net.EndpointReach.LAN, reach("http://home-box.local:11434"))
        assertEquals(com.rokid.context.core.net.EndpointReach.DEVICE, reach("anything", EndpointKind.LLAMACPP_LOCAL))
    }

    // ---- the setting is read at every call, and fails closed ----

    @Test fun theSettingIsReadAtEveryCallSoAnAutoOnlyRouterCanBeSwitchedBothWays() = runTest {
        ep("r", caps = chat); bindings.set(RoleBinding(Role.ANSWER, "r", "m")).getOrThrow()
        mode = { ModelPrivacy.AUTO }
        assertEquals("r/m", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.text)
        mode = { ModelPrivacy.OFFLINE_ONLY }
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is OfflineOnlyRefusedException)
        mode = { ModelPrivacy.AUTO }
        assertEquals("r/m", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.text)
        assertEquals(listOf("r:text", "r:text"), providerCalls)
    }

    @Test fun aSettingReaderThatThrowsCountsAsOfflineOnly() = runTest {
        ep("r", caps = chat); bindings.set(RoleBinding(Role.ANSWER, "r", "m")).getOrThrow()
        mode = { throw IllegalStateException("prefs unreadable") }
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is OfflineOnlyRefusedException)
        assertNothingReachedAWire()
    }

    @Test fun autoIsExactlyTheOldBehaviour() = runTest {
        mode = { ModelPrivacy.AUTO }
        ep("r", caps = chat); ep("v", caps = vision)
        bindings.set(RoleBinding(Role.ANSWER, "r", "m")).getOrThrow(); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        assertEquals("r/m", router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.text)
        assertTrue(router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }.imagesDelivered)
        assertFalse(permits.isEmpty())
    }

    // ---- user-facing ----

    @Test fun theRefusalMapsToTheNeedsAnOnDeviceModelState() {
        assertEquals(UserFacingErrors.AiNeedsOnDevice, ModelErrorMapper.toUserFacing(OfflineOnlyRefusedException()))
        assertTrue(UserFacingErrors.AiNeedsOnDevice.title.contains("on-device"))
        assertTrue(UserFacingErrors.AiNeedsOnDevice in UserFacingErrors.all)
        assertEquals(UserFacingErrors.AiUnavailable, ModelErrorMapper.toUserFacing(DataSaverBlockedException("wifi_only")))
    }

    // ---- embeddings and the endpoint test ----

    @Test fun anEmbeddingCallToARemoteEndpointIsRefusedBeforeAnythingIsSentButTheOnDeviceModelStillEmbeds() = runTest {
        val nativeCalls = mutableListOf<String>()
        val client = EmbeddingClient(native = { path, texts, _ -> nativeCalls += path; texts.map { floatArrayOf(1f, 0f) } }, privacy = { mode() })
        val remoteEp = Endpoint("r", "r", EndpointKind.OLLAMA, remote, null, listOf("m"), setOf(Capability.EMBEDDINGS), 1)
        val local = Endpoint("l", "l", EndpointKind.LLAMACPP_LOCAL, "model.gguf", null, listOf("m"), setOf(Capability.EMBEDDINGS), 1)
        assertTrue(err { client.embed(remoteEp, "m", null, listOf("hello")) } is OfflineOnlyRefusedException)
        assertTrue(err { client.revision(remoteEp, "m") } is OfflineOnlyRefusedException)
        assertEquals(1, client.embed(local, "m", null, listOf("hello")).size)
        assertEquals(listOf("model.gguf"), nativeCalls)
    }

    @Test fun testingARemoteEndpointIsRefusedUnderOfflineOnlyWithoutReadingItsKeyOrBuildingAProvider() = runTest {
        ep("r", caps = chat)
        val tester = EndpointTester(endpoints, providers, privacy = { mode() })
        val res = tester.test("r", "m")
        assertFalse(res.ok); assertEquals(EndpointTester.OFFLINE_ONLY_MESSAGE, res.message)
        assertTrue(built.isEmpty())
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = chat) // the phone's own model is not refused (it fails later for lack of a real model file, which is not our concern here)
        assertTrue(tester.test("loc", "m").message != EndpointTester.OFFLINE_ONLY_MESSAGE)
    }
}
