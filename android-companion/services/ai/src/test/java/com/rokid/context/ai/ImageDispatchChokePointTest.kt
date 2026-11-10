package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ImageUse
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EndpointEntity
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.GonePhotos
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.prompt.BuiltInPromptSeeds
import com.rokid.context.prompt.InMemoryPersonaSettings
import com.rokid.context.prompt.InMemoryPromptSettings
import com.rokid.context.prompt.PromptRepository
import com.rokid.context.prompt.PromptVars
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex r10: the image-dispatch guard lives in ModelRouter.call, the one path every provider call takes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImageDispatchChokePointTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    /** Ordered events: permit, slot, check, send:<id>:<images>, release. */
    private val events = mutableListOf<String>()
    private var acceptsImages = true
    private var visionFails = false
    private val providers = ProviderBuilder { e, _, _ ->
        object : ModelProvider {
            override val acceptsImages: Boolean get() = this@ImageDispatchChokePointTest.acceptsImages
            override suspend fun generate(prompt: String): String = error("unused")
            override suspend fun complete(request: ModelRequest): ModelReply {
                events += "send:${e.id}:${request.images.size}"
                if (visionFails && request.images.isNotEmpty()) throw ProviderHttpException(500, "x", "")
                return ModelReply("""{"ok":true}""")
            }
        }
    }
    private val gate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { events += "permit"; return block() }
    }
    private val router = ModelRouter(bindings, endpoints, providers, gate)
    @After fun close() = db.close()

    private inner class Live(var gone: Set<String> = emptySet(), var wiped: Boolean = false, var goneMemories: Set<String> = emptySet()) : PhotoLiveness {
        override suspend fun goneMemories(ids: Set<String>): Set<String> { events += "mem:${ids.sorted().joinToString(",")}"; return ids intersect goneMemories }
        override suspend fun <T> holdUploadSlot(block: suspend () -> T): T { events += "slot"; try { return block() } finally { events += "release" } }
        override suspend fun gone(ids: Set<String>): GonePhotos { events += "check:${ids.sorted().joinToString(",")}"; return GonePhotos(ids intersect gone, wiped) }
    }

    private suspend fun ep(id: String, kind: EndpointKind = EndpointKind.OLLAMA, vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, kind, "http://localhost:1", null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA", ref = "f1"))
    private suspend fun err(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull()!!

    @Test fun permitThenSlotThenRecheckThenUploadThenRelease() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        val r = router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, requireImages = true, liveness = Live()) }
        assertEquals(listOf("permit", "slot", "check:f1", "send:v:1", "release"), events)
        assertTrue("delivery proof", r.imagesDelivered)
    }

    @Test fun aForgottenPhotoIsDroppedUnsentAndNeverRetriedAnywhere() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); ep("w", caps = arrayOf(Capability.CHAT, Capability.VISION)); ep("t", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m", fallbackEndpointId = "w", fallbackModel = "m")).getOrThrow()
        bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        // an OPTIONAL-image request (it would normally retry as text): a forgotten photo still never leaves, as photo or as text, on any endpoint
        val e = err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q", img, liveness = Live(gone = setOf("f1"))) } }
        assertTrue(e.toString(), e is PhotoForgottenException)
        assertEquals(setOf("f1"), (e as PhotoForgottenException).frameIds)
        assertTrue("nothing was sent: $events", events.none { it.startsWith("send") })
        assertEquals("one re-check, no fallback, no SUMMARIZE chain", 1, events.count { it.startsWith("check") })
    }

    @Test fun deleteAllSinceLoadDropsTheCall() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.ANSWER, "v", "m")).getOrThrow()
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", img, liveness = Live(wiped = true)) } }
        assertTrue(e is PhotoForgottenException && e.wiped)
        assertTrue(events.none { it.startsWith("send") })
    }

    // codex r12: a text-only prompt built from stored memories is guarded like a photo upload
    @Test fun aForgottenSourceMemoryDropsATextCallUnsent() = runTest {
        ep("t", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "t", "m")).getOrThrow()
        val src = com.rokid.context.model.PayloadSources(frameIds = setOf("f9"), memoryIds = setOf("m1", "m2"))
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", sources = src, liveness = Live(goneMemories = setOf("m2"))) } }
        assertTrue(e.toString(), e is PhotoForgottenException && e.memoryIds == setOf("m2"))
        assertEquals(listOf("permit", "slot", "check:f9", "mem:m1,m2", "release"), events)
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", sources = src) } } is ImagesUnguardedException)
        assertTrue(events.none { it.startsWith("send") })
    }

    @Test fun aPhotoWithoutRefOrLivenessIsRefusedUnsent() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.ANSWER, "v", "m")).getOrThrow()
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", img) } } is ImagesUnguardedException)
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", listOf(ModelImageInput("image/jpeg", "AAAA")), liveness = Live()) } } is ImagesUnguardedException)
        assertTrue(events.none { it.startsWith("send") })
    }

    @Test fun anOptionalPhotoTextRetryIsRecheckedAndCarriesNoDeliveryProof() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.ANSWER, "v", "m")).getOrThrow()
        visionFails = true
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q", img, liveness = Live()) }
        assertEquals(2, events.count { it.startsWith("check") })
        assertEquals("send:v:0", events.last { it.startsWith("send") })
        assertFalse(r.imagesDelivered)
    }

    @Test fun aProviderThatCannotSendImagesFailsAPhotoCallClosed() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        acceptsImages = false
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, requireImages = true, liveness = Live()) } }
        assertTrue(e.toString(), ModelRouter.needsVision(e))
        assertTrue("no output, nothing sent", events.none { it.startsWith("send") })
    }

    @Test fun aLocalEndpointMarkedVisionIsRefusedAtRunTime() = runTest {
        // a row stored before the save rule existed: the router still fails closed
        db.endpointDao().upsert(EndpointEntity("loc", "loc", EndpointKind.LLAMACPP_LOCAL.name, "/m.gguf", null, "local", "", "CHAT,VISION", 1))
        db.roleBindingDao().upsert(com.rokid.context.memory.db.RoleBindingEntity(Role.VISION_EXTRACT.name, "loc", "local", null, null, null, null))
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, requireImages = true, liveness = Live()) } }
        assertTrue(e.toString(), ModelRouter.needsVision(e))
    }

    @Test fun aLocalEndpointCannotBeSavedWithVisionNorBoundToAVisionRole() = runTest {
        val saved = endpoints.save(Endpoint("loc", "loc", EndpointKind.LLAMACPP_LOCAL, "/m.gguf", null, listOf("local"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = null)
        assertEquals(EndpointRules.NO_IMAGES_MESSAGE, saved.exceptionOrNull()?.message)
        db.endpointDao().upsert(EndpointEntity("old", "old", EndpointKind.LLAMACPP_LOCAL.name, "/m.gguf", null, "local", "", "CHAT,VISION", 1))
        val bound = bindings.set(RoleBinding(Role.VISION_EXTRACT, "old", "local"))
        assertEquals(EndpointRules.NO_IMAGES_MESSAGE, bound.exceptionOrNull()?.message)
        assertTrue(bindings.eligibleEndpoints(Role.VISION_EXTRACT).none { it.id == "old" })
    }

    @Test fun theNativeProviderRefusesAPhotoInsteadOfDroppingIt() = runTest {
        val never = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.rokid.context.model.provider.LlamaNative::class.java)) { _, m, _ -> error("native ${m.name} must not run") }
            as com.rokid.context.model.provider.LlamaNative
        val p = com.rokid.context.model.provider.LlamaCppNativeProvider("/m.gguf", never)
        assertFalse(p.acceptsImages)
        assertTrue(err { p.complete(ModelRequest("q", img)) } is ImagesUnsupportedException)
    }

    // ---- RoleRunner reads the one PromptKey declaration

    private fun runner(capture: MutableList<ModelRequest>) = RoleRunner(object : com.rokid.context.model.LanguageModel {
        val e = Endpoint("v", "v", EndpointKind.OLLAMA, "http://x", null, listOf("m"), setOf(Capability.CHAT, Capability.VISION), 1)
        fun reply() = RoutedReply("{}", e, "m", 1, null, null)
        override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest) = reply().also { capture += build(e) }
        override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest) = reply().also { capture += build(e) }
        override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest) = reply().also { capture += build(e) }
    }, PromptRepository(db.promptDao(), BuiltInPromptSeeds, InMemoryPromptSettings()), InMemoryPersonaSettings())

    @Test fun everyPhotoRequiringKeyAlwaysRequiresItsImagesClusterSummaryIncluded() = runTest {
        for (key in PromptKey.entries.filter { it.requiresImage }) {
            val sent = mutableListOf<ModelRequest>()
            runCatching { runner(sent).run(RoleCall(key, PromptVars(), images = img, liveness = AllLive, fromFrames = true)) }
            assertTrue("$key made a call", sent.isNotEmpty())
            assertTrue("$key: every attempt requires its photo and carries the liveness", sent.all { it.requireImages && it.images.isNotEmpty() && it.liveness === AllLive && it.sources.frameIds == setOf("f1") })
        }
        assertTrue(PromptKey.CLUSTER_SUMMARY.requiresImage)
    }

    @Test fun aPhotoRequiringKeyWithoutItsPhotoIsRefusedAndANoImageKeyRefusesPhotos() = runTest {
        val sent = mutableListOf<ModelRequest>()
        assertTrue(err { runner(sent).run(RoleCall(PromptKey.CLUSTER_SUMMARY, PromptVars())) } is ImagesUnsupportedException)
        val none = PromptKey.entries.first { it.role != null && it.imageUse == ImageUse.NONE }
        assertTrue(err { runner(sent).run(RoleCall(none, PromptVars(), images = img, liveness = AllLive)) } is ImagesUnguardedException)
        assertTrue(sent.isEmpty())
    }
}
