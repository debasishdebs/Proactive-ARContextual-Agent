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
}
