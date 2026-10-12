package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EndpointEntity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointTesterTest {
    private val server = MockWebServer().apply { start() }
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() { server.shutdown(); db.close() }

    private val vault = InMemoryKeyVault()
    private val repo = EndpointRepository(db.endpointDao(), vault)
    private val tester = EndpointTester(repo, ProviderFactory)

    /** Writes straight to the DAO so the (http) MockWebServer can stand in for https-only Azure. */
    private suspend fun save(kind: EndpointKind, caps: Set<Capability>, url: String = server.url("/").toString().trimEnd('/')) {
        db.endpointDao().upsert(
            EndpointEntity("e", "t", kind.name, url, "2024-10-21", "m", EndpointRepository.keyRef("e"), caps.joinToString(",") { it.name }, 1),
        )
        vault.put(EndpointRepository.keyRef("e"), "k")
    }

    @Test fun azureChatSuccess() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        val r = tester.test("e", "m")
        assertTrue(r.ok)
        assertTrue(server.takeRequest().path!!.startsWith("/openai/deployments/m/chat/completions?api-version=2024-10-21"))
    }

    @Test fun openAiCompatUsesBearerAndModelField() = runTest {
        save(EndpointKind.OPENAI_COMPAT, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        assertTrue(tester.test("e", "m").ok)
        val req = server.takeRequest()
        assertEquals("/chat/completions", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"model\":\"m\""))
    }

    @Test fun embeddingsOnlyEndpointIsTestedWithAnEmbedding() = runTest {
        save(EndpointKind.OLLAMA, setOf(Capability.EMBEDDINGS))
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.1,0.2,0.3]]}"""))
        val r = tester.test("e", "m")
        assertTrue(r.ok); assertTrue(r.message.contains("3"))
        assertEquals("/api/embed", server.takeRequest().path)
    }

    @Test fun failureMessageIsShortAndHuman() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Access denied due to invalid subscription key."}}"""))
        val r = tester.test("e", "m")
        assertFalse(r.ok)
        assertEquals("Key rejected (401)", r.message)
    }

    private val redirectMsg = "Address redirects — enter the final address"

    @Test fun chatRedirectIsNotFollowedAndKeyNotForwarded() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/x").toString()))
            val r = tester.test("e", "m")
            assertFalse(r.ok)
            assertEquals(redirectMsg, r.message)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun embeddingRedirectIsNotFollowedAndKeyNotForwarded() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            save(EndpointKind.AZURE_OPENAI, setOf(Capability.EMBEDDINGS))
            server.enqueue(MockResponse().setResponseCode(308).setHeader("Location", other.url("/x").toString()))
            val r = tester.test("e", "m")
            assertEquals(redirectMsg, r.message)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
}
