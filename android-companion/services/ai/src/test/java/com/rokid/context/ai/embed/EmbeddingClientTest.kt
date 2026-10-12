package com.rokid.context.ai.embed

import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.ProviderResponseException
import com.rokid.context.ai.Http
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingClientTest {
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    @After fun stop() = server.shutdown()
    private val client = EmbeddingClient(Http.modelClient(60).newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build())
    private fun e(kind: EndpointKind) = Endpoint("e", "E", kind, "https://localhost:${server.port}", "2024-10-21", listOf("m"), setOf(Capability.EMBEDDINGS), 1)

    @Test fun ollamaUsesApiEmbed() = runTest {
        server.enqueue(MockResponse().setBody("""{"embeddings":[[0.1,0.2],[0.3,0.4]]}"""))
        val v = client.embed(e(EndpointKind.OLLAMA), "nomic-embed-text", null, listOf("a", "b"))
        assertArrayEquals(floatArrayOf(0.3f, 0.4f), v[1], 1e-6f)
        val req = server.takeRequest()
        assertEquals("/api/embed", req.path)
        assertEquals(2, Json.parseToJsonElement(req.body.readUtf8()).jsonObject["input"]!!.jsonArray.size)
    }

    @Test fun azureUsesTheDeploymentPathKeyHeaderAndIndexOrder() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"index":1,"embedding":[2,2]},{"index":0,"embedding":[1,1]}]}"""))
        val v = client.embed(e(EndpointKind.AZURE_OPENAI), "emb", "k", listOf("a", "b"), dimensions = 2)
        assertArrayEquals(floatArrayOf(1f, 1f), v[0], 0f)
        val req = server.takeRequest()
        assertEquals("/openai/deployments/emb/embeddings?api-version=2024-10-21", req.path)
        assertEquals("k", req.getHeader("api-key"))
        assertEquals("2", Json.parseToJsonElement(req.body.readUtf8()).jsonObject["dimensions"]!!.jsonPrimitive.content)
    }

    @Test fun openAiCompatibleUsesBearerAndModel() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1]}]}"""))
        client.embed(e(EndpointKind.OPENAI_COMPAT), "bge-m3", "k", listOf("a"))
        val req = server.takeRequest()
        assertEquals("/embeddings", req.path); assertEquals("Bearer k", req.getHeader("Authorization"))
        assertEquals("bge-m3", Json.parseToJsonElement(req.body.readUtf8()).jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test fun errorsAreTypedAndOllamaRevisionComesFromTags() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(401, (runCatching { client.embed(e(EndpointKind.OPENAI_COMPAT), "m", "k", listOf("a")) }.exceptionOrNull() as ProviderHttpException).code)
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"nomic-embed-text:latest","digest":"sha256:abc"}]}"""))
        assertEquals("sha256:abc", client.revision(e(EndpointKind.OLLAMA), "nomic-embed-text"))
        assertEquals("/embeddings", server.takeRequest().path)
        assertEquals("/api/tags", server.takeRequest().path)
    }

    private suspend fun failure(body: String, inputs: List<String> = listOf("a", "b")): Throwable? {
        server.enqueue(MockResponse().setBody(body))
        return runCatching { client.embed(e(EndpointKind.OPENAI_COMPAT), "m", "k", inputs) }.exceptionOrNull()
    }

    @Test fun aWrongCountIsATypedError() = runTest {
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"embeddings":[[1,2],[3,4],[5,6]]}""") is ProviderResponseException)
    }

    @Test fun inconsistentOrEmptyDimensionsAreTypedErrors() = runTest {
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,2]},{"index":1,"embedding":[1,2,3]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":[]},{"index":1,"embedding":[]}]}""") is ProviderResponseException)
    }

    @Test fun nonFiniteOrNonNumericValuesAreTypedErrors() = runTest {
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,"NaN"]},{"index":1,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,1e999]},{"index":1,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,"x"]},{"index":1,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,null]},{"index":1,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":[1,2]},{"index":0,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("""{"data":[{"index":0,"embedding":"nope"},{"index":1,"embedding":[1,2]}]}""") is ProviderResponseException)
        assertTrue(failure("not json") is ProviderResponseException)
        assertTrue(failure("""{"other":1}""") is ProviderResponseException)
    }

    @Test fun aKeyIsNeverSentOverHttp() = runTest {
        val plain = MockWebServer().apply { start() }
        try {
            val url = plain.url("/").toString().trimEnd('/')
            val c = EmbeddingClient()
            for (kind in listOf(EndpointKind.OPENAI_COMPAT, EndpointKind.AZURE_OPENAI)) {
                plain.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1]}]}"""))
                val ex = runCatching { c.embed(Endpoint("e", "E", kind, url, null, listOf("m"), setOf(Capability.EMBEDDINGS), 1), "m", "k", listOf("a")) }.exceptionOrNull()
                assertTrue(ex is ProviderConfigException)
            }
            assertEquals(0, plain.requestCount)
        } finally { plain.shutdown() }
    }

    @Test fun anAzureEndpointWithoutAKeyIsAConfigError() = runTest {
        assertTrue(runCatching { client.embed(e(EndpointKind.AZURE_OPENAI), "m", null, listOf("a")) }.exceptionOrNull() is ProviderConfigException)
        assertEquals(0, server.requestCount)
    }

    @Test fun anAzureDeploymentNameIsPathEncoded() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"index":0,"embedding":[1]}]}"""))
        client.embed(e(EndpointKind.AZURE_OPENAI), "my dep/x", "k", listOf("a"))
        assertEquals("/openai/deployments/my%20dep%2Fx/embeddings?api-version=2024-10-21", server.takeRequest().path)
    }

    @Test fun aFailedRevisionLookupIsAnErrorNotNull() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(runCatching { client.revision(e(EndpointKind.OLLAMA), "m") }.exceptionOrNull() is ProviderHttpException)
        server.enqueue(MockResponse().setBody("""{"models":[]}"""))
        assertEquals(null, client.revision(e(EndpointKind.OLLAMA), "m"))
        assertEquals(null, client.revision(e(EndpointKind.OPENAI_COMPAT), "m"))
    }

    @Test fun providerBodyInTheExceptionIsCapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(5_000)))
        val ex = runCatching { client.embed(e(EndpointKind.OPENAI_COMPAT), "m", "k", listOf("a")) }.exceptionOrNull() as ProviderHttpException
        assertTrue(ex.message!!.length < 260)
    }

    @Test fun embedderBatchesDocumentsInOrder() = runTest {
        val spec = EmbeddingSpec("e", EndpointKind.OPENAI_COMPAT, "https://localhost:${server.port}", null, "m", null, null, 1, false, "", "", 100)
        val emb = EndpointEmbedder(spec, e(EndpointKind.OPENAI_COMPAT), "k", client)
        repeat(3) { n ->
            val count = if (n < 2) 64 else 22
            val rows = (0 until count).joinToString(",") { """{"index":$it,"embedding":[${n * 64 + it}]}""" }
            server.enqueue(MockResponse().setBody("""{"data":[$rows]}"""))
        }
        val out = emb.embedDocuments((0 until 150).map { "t$it" })
        assertEquals(3, server.requestCount)
        assertEquals((0 until 150).map { it.toFloat() }, out.map { it[0] })
    }
}
