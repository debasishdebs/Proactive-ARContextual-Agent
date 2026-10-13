package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EndpointEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import com.rokid.context.model.ModelProvider
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import com.rokid.context.model.provider.OpenAiCompatProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .followRedirects(false).followSslRedirects(false).build()
    private val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    private fun httpsUrl() = "https://localhost:${server.port}"
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() { server.shutdown(); db.close() }

    private val vault = InMemoryKeyVault()
    private val repo = EndpointRepository(db.endpointDao(), vault)
    /** Same wiring as [ProviderFactory], with a client that trusts the test certificate. */
    private val tlsProviders = ProviderBuilder { e, model, key ->
        when (e.kind) {
            EndpointKind.AZURE_OPENAI -> OpenAiCompatProvider(e.baseUrl, key.orEmpty(), model, e.apiVersion ?: "2024-10-21", OpenAiCompatProvider.ApiStyle.AZURE, client)
            else -> OpenAiCompatProvider(e.baseUrl, key.orEmpty(), model, e.apiVersion ?: "", OpenAiCompatProvider.ApiStyle.OPENAI, client)
        }
    }
    private val tester = EndpointTester(repo, tlsProviders, client)
    private val strictTester = EndpointTester(repo, ProviderFactory)

    /** Writes straight to the DAO so the (http) MockWebServer can stand in for https-only Azure. */
    private suspend fun save(kind: EndpointKind, caps: Set<Capability>, url: String = httpsUrl()) {
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

    @Test fun aKeyIsNeverSentOverHttpEvenForAnOldRow() = runTest {
        val plain = MockWebServer().apply { start() }
        try {
            val url = plain.url("/").toString().trimEnd('/')
            save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT), url)
            plain.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
            assertFalse(strictTester.test("e", "m").ok)
            save(EndpointKind.OPENAI_COMPAT, setOf(Capability.EMBEDDINGS), url)
            plain.enqueue(MockResponse().setBody("""{"data":[{"embedding":[0.1]}]}"""))
            assertFalse(strictTester.test("e", "m").ok)
            assertEquals(0, plain.requestCount)
        } finally { plain.shutdown() }
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

    /** Fix round 1 (I-4): a successful chat Test shows a fixed "OK", never the model's reply. */
    @Test fun chatSuccessNeverEchoesModelText() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"IGNORE ME"}}]}"""))
        val r = tester.test("e", "m")
        assertTrue(r.ok); assertEquals("OK", r.message)
    }

    /** Fix round 1 (I-3): Test runs on the tester's own bounded client, built from the injected one, for every HTTP kind. */
    private fun bounded() = EndpointTester(repo, ProviderFactory, client, connectTimeoutMs = 300, callTimeoutMs = 300)

    @Test fun chatTestIsBoundedByTheTestCallTimeout() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val r = bounded().test("e", "m")
        assertEquals("Timed out", r.message); assertTrue("took ${r.latencyMs} ms", r.latencyMs < 2_500)
    }

    @Test fun ollamaChatTestIsBoundedByTheTestCallTimeout() = runTest {
        save(EndpointKind.OLLAMA, setOf(Capability.CHAT))
        server.enqueue(MockResponse().setBody("""{"response":"OK"}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val r = bounded().test("e", "m")
        assertEquals("Timed out", r.message); assertTrue("took ${r.latencyMs} ms", r.latencyMs < 2_500)
    }

    @Test fun embeddingTestIsBoundedByTheTestCallTimeout() = runTest {
        save(EndpointKind.OLLAMA, setOf(Capability.EMBEDDINGS))
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.1]]}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val r = bounded().test("e", "m")
        assertEquals("Timed out", r.message); assertTrue("took ${r.latencyMs} ms", r.latencyMs < 2_500)
    }

    @Test fun localChatTestIsBoundedByTheOwnedScopeTimeout() = runTest {
        save(EndpointKind.LLAMACPP_LOCAL, setOf(Capability.CHAT), url = "/sdcard/m.gguf")
        val never = CompletableDeferred<String>()
        val stuck = ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String): String = never.await() } }
        val r = EndpointTester(repo, stuck, client, callTimeoutMs = 300).test("e", "m")
        assertEquals("Timed out", r.message)
    }

    /** Fix round 1 (M-7): cancellation is not turned into a "Test failed" result. */
    @Test fun cancellationIsRethrown() = runTest {
        save(EndpointKind.AZURE_OPENAI, setOf(Capability.CHAT))
        val cancelling = ProviderBuilder { _, _, _ -> object : ModelProvider { override suspend fun generate(prompt: String): String = throw CancellationException("left the screen") } }
        val thrown = runCatching { EndpointTester(repo, cancelling, client).test("e", "m") }.exceptionOrNull()
        assertTrue("got $thrown", thrown is CancellationException)
    }
}
