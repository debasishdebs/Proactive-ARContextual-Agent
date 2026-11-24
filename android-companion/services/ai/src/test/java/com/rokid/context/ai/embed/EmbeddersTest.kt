package com.rokid.context.ai.embed

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.embed.EmbeddingClient
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.SpaceState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EmbeddersTest {
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val vault = InMemoryKeyVault()
    private val endpoints = EndpointRepository(db.endpointDao(), vault)
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val embedders = Embedders(endpoints, bindings)
    @After fun stop() { a.shutdown(); b.shutdown(); db.close() }

    /** Saved through the DAO so the http MockWebServer host needs no cleartext allowance (same trick as EndpointTesterTest). */
    private suspend fun ollamaAt(server: MockWebServer) {
        db.endpointDao().upsert(com.rokid.context.memory.db.EndpointEntity("ol", "Ollama", "OLLAMA", server.url("/").toString().trimEnd('/'), null,
            "nomic-embed-text", EndpointRepository.keyRef("ol"), "EMBEDDINGS", 1))
    }

    @Test fun forBindingProbesDimensionRevisionAndPrefixes() = runTest {
        assertNull(embedders.forBinding())
        ollamaAt(a); bindings.set(RoleBinding(Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
        a.enqueue(MockResponse().setBody("""{"models":[{"name":"nomic-embed-text:latest","digest":"sha256:abc"}]}"""))
        a.enqueue(MockResponse().setBody("""{"embeddings":[[3,4,0]]}"""))
        val emb = embedders.forBinding()!!
        assertEquals(3, emb.spec.dim); assertEquals("sha256:abc", emb.spec.revision); assertEquals("search_query: ", emb.spec.queryPrefix)
        a.takeRequest()
        assertTrue(Json.parseToJsonElement(a.takeRequest().body.readUtf8()).jsonObject["input"]!!.jsonArray.single().jsonPrimitive.content.startsWith("search_query: "))
        a.enqueue(MockResponse().setBody("""{"embeddings":[[3,4,0]]}"""))
        val q = emb.embedQuery("milk")
        assertEquals(0.6f, q[0], 1e-6f) // L2-normalised
    }

    /** Final-review I1 (ruling): a moved endpoint makes its old space unavailable (lexical fallback); no text goes to the old host. */
    @Test fun forSpaceIsUnavailableOnceTheEndpointMoved() = runTest {
        ollamaAt(a)
        val spec = EmbeddingSpec("ol", EndpointKind.OLLAMA, a.url("/").toString().trimEnd('/'), null, "nomic-embed-text", null, null, 2, true, "", "", 2_000)
        val space = EmbeddingSpaceEntity("s1", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1)
        a.enqueue(MockResponse().setBody("""{"embeddings":[[1,0]]}"""))
        embedders.forSpace(space)!!.embedQuery("q") // same target: still served
        assertEquals(1, a.requestCount)
        ollamaAt(b) // the user pointed the endpoint at another server
        assertNull(embedders.forSpace(space))
        assertEquals(1, a.requestCount); assertEquals(0, b.requestCount)
        endpoints.delete("ol")
        assertNull(embedders.forSpace(space))
    }

    /** mp-r2 P2: under Offline only a remote EMBED endpoint is refused BEFORE its key is read from the vault, in both factories. */
    @Test fun offlineOnlyRefusesARemoteEndpointBeforeTheKeyIsRead() = runTest {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val counting = object : com.rokid.context.ai.KeyVault {
            override fun get(ref: String): String? { reads.incrementAndGet(); return "SECRET" }
            override fun put(ref: String, key: String) {}
            override fun remove(ref: String) {}
        }
        val eps = EndpointRepository(db.endpointDao(), counting)
        val offline = Embedders(eps, bindings, privacy = { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY })
        db.endpointDao().upsert(com.rokid.context.memory.db.EndpointEntity("oa", "OA", "OPENAI_COMPAT", "https://api.example.com", null, "m", EndpointRepository.keyRef("oa"), "EMBEDDINGS", 1))
        bindings.set(RoleBinding(Role.EMBED, "oa", "m")).getOrThrow()
        assertTrue(runCatching { offline.forBinding() }.exceptionOrNull() is com.rokid.context.ai.OfflineOnlyRefusedException)
        val spec = EmbeddingSpec("oa", EndpointKind.OPENAI_COMPAT, "https://api.example.com", null, "m", null, null, 2, true, "", "", 2_000)
        assertNull(offline.forSpace(EmbeddingSpaceEntity("s", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1)))
        assertEquals("the vault was never asked", 0, reads.get())
        // a throwing mode reader fails closed too
        assertTrue(runCatching { Embedders(eps, bindings, privacy = { error("prefs") }).forBinding() }.exceptionOrNull() is com.rokid.context.ai.OfflineOnlyRefusedException)
        assertEquals(0, reads.get())
    }

    @Test fun offlineOnlyStillServesALoopbackSpace() = runTest {
        ollamaAt(a)
        val spec = EmbeddingSpec("ol", EndpointKind.OLLAMA, a.url("/").toString().trimEnd('/'), null, "nomic-embed-text", null, null, 2, true, "", "", 2_000)
        val space = EmbeddingSpaceEntity("s1", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1)
        a.enqueue(MockResponse().setBody("""{"embeddings":[[1,0]]}"""))
        val off = Embedders(endpoints, bindings, EmbeddingClient(privacy = { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY }), privacy = { com.rokid.context.core.net.ModelPrivacy.OFFLINE_ONLY })
        off.forSpace(space)!!.embedQuery("q")
        assertEquals(1, a.requestCount)
    }

    @Test fun forSpaceIsUnavailableOnceTheEndpointKindChanged() = runTest {
        ollamaAt(a)
        val url = a.url("/").toString().trimEnd('/')
        val spec = EmbeddingSpec("ol", EndpointKind.OPENAI_COMPAT, url, null, "nomic-embed-text", null, null, 2, true, "", "", 2_000)
        assertNull(embedders.forSpace(EmbeddingSpaceEntity("s1", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1)))
        assertEquals(0, a.requestCount)
    }

    @Test fun forSpaceComparesNormalisedAddresses() = runTest {
        ollamaAt(a)
        val spaced = a.url("/").toString().replace("localhost", "LOCALHOST") + "/" // same server, different spelling
        val spec = EmbeddingSpec("ol", EndpointKind.OLLAMA, spaced, null, "nomic-embed-text", null, null, 2, true, "", "", 2_000)
        a.enqueue(MockResponse().setBody("""{"embeddings":[[1,0]]}"""))
        embedders.forSpace(EmbeddingSpaceEntity("s1", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1))!!.embedQuery("q")
        assertEquals(1, a.requestCount)
    }

    @Test fun aDimensionChangeIsAnError() = runTest {
        ollamaAt(a)
        val spec = EmbeddingSpec("ol", EndpointKind.OLLAMA, a.url("/").toString().trimEnd('/'), null, "m", null, null, 2, true, "", "", 2_000)
        a.enqueue(MockResponse().setBody("""{"embeddings":[[1,0,0]]}"""))
        val e = runCatching { embedders.forSpace(EmbeddingSpaceEntity("s", spec.fingerprint, spec.configJson(), 2, SpaceState.ACTIVE, "[]", 1))!!.embedQuery("q") }.exceptionOrNull()
        assertTrue(e is ProviderResponseException)
    }

    @Test fun aRepointedEndpointNeverReceivesItsNewKeyAtTheOldHost() = runTest {
        val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val old = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        try {
            val tls = EmbeddingClient(com.rokid.context.ai.Http.modelClient(60).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
            val emb = Embedders(endpoints, bindings, tls)
            val oldUrl = "https://localhost:${old.port}"
            val spec = EmbeddingSpec("oa", EndpointKind.OPENAI_COMPAT, oldUrl, null, "m", null, null, 1, false, "", "", 100)
            val space = EmbeddingSpaceEntity("s", spec.fingerprint, spec.configJson(), 1, SpaceState.ACTIVE, "[]", 1)
            db.endpointDao().upsert(com.rokid.context.memory.db.EndpointEntity("oa", "OA", "OPENAI_COMPAT", "https://new.example.com", null, "m", EndpointRepository.keyRef("oa"), "EMBEDDINGS", 1))
            vault.put(EndpointRepository.keyRef("oa"), "NEWKEY")
            old.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1]}]}"""))
            runCatching { emb.forSpace(space)!!.embedQuery("q") }
            val req = if (old.requestCount > 0) old.takeRequest() else null
            assertNull(req?.getHeader("Authorization")); assertNull(req?.getHeader("api-key"))
        } finally { old.shutdown() }
    }

    // runBlocking, not runTest: the on-device embed runs on a real thread under a withTimeout, and runTest's virtual clock jumps past that timeout while the thread is still starting (a flake under load).
    @Test fun bindingWritesImageDim() = kotlinx.coroutines.runBlocking {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "nomic_binding_test").apply { mkdirs() }
        val gguf = java.io.File(dir, "nomic-embed-text-v1.5.Q8_0.gguf").path
        db.endpointDao().upsert(com.rokid.context.memory.db.EndpointEntity("local_nomic", "Nomic", "LLAMACPP_LOCAL", gguf, null,
            com.rokid.context.ai.local.NomicText.CATALOG_ID, EndpointRepository.keyRef("local_nomic"), "EMBEDDINGS", 1))
        bindings.set(RoleBinding(Role.EMBED, "local_nomic", com.rokid.context.ai.local.NomicText.CATALOG_ID)).getOrThrow()
        val nomic = Embedders(endpoints, bindings, EmbeddingClient(native = { _, t, _ -> t.map { FloatArray(768) { i -> i + 1f } } })).forBinding()!!
        assertEquals(com.rokid.context.ai.local.NomicVision.STORED_DIMS, nomic.spec.imageDim)
        assertEquals(com.rokid.context.ai.local.NomicBundle.fingerprint(768), nomic.fingerprint)
        assertEquals(768, EmbeddingSpec.fromConfig(nomic.spec.configJson())!!.imageDim)
        val text = com.rokid.context.ai.local.NomicBundle.visionPath(gguf)
        assertEquals("sibling of the GGUF", java.io.File(dir, com.rokid.context.ai.local.NomicBundle.VISION_FILE), text)
    }

    @Test fun oldConfigWithoutImageDimParses() {
        val old = """{"apiVersion":null,"baseUrl":"/m/n.gguf","dim":256,"dimensions":null,"docPrefix":"search_document: ","kind":"LLAMACPP_LOCAL","l2":true,"maxInputChars":2000,"model":"nomic-embed-v1.5","queryPrefix":"search_query: ","revision":null,"endpointId":"l"}"""
        val spec = EmbeddingSpec.fromConfig(old)!!
        assertNull(spec.imageDim)
        assertEquals("a null imageDim leaves the identity json (and every text-only fingerprint) unchanged", false, "imageDim" in spec.identityJson())
    }
}
