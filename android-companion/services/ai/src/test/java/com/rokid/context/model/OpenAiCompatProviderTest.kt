package com.rokid.context.model

import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.model.provider.OpenAiCompatProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import com.rokid.context.ai.Http
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatProviderTest {
    // A key is never sent over http (KeyTransport.requireSecure), so the fixture serves https and trusts its own certificate.
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val client = Http.modelClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
    private val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    @After fun stop() = server.shutdown()
    private fun url() = server.url("/").toString().trimEnd('/')
    private val ok = """{"choices":[{"message":{"content":"{\"answer\":\"hi\"}"}}],"usage":{"prompt_tokens":12,"completion_tokens":5}}"""

    @Test fun strictJsonSchemaTemperatureAndTokensAreSentAndUsageRead() = runTest {
        server.enqueue(MockResponse().setBody(ok))
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val reply = OpenAiCompatProvider(url(), "k", "gpt", style = OpenAiCompatProvider.ApiStyle.AZURE, client = client)
            .complete(ModelRequest("q", temperature = 0.7, maxTokens = 64, format = WireFormat.JsonSchema("ANSWER", schema)))
        assertEquals("{\"answer\":\"hi\"}", reply.text)
        assertEquals(12, reply.promptTokens); assertEquals(5, reply.completionTokens)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0.7", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("64", body["max_completion_tokens"]!!.jsonPrimitive.content)
        val rf = body["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        assertEquals("true", rf["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.content)
        assertEquals(schema, rf["json_schema"]!!.jsonObject["schema"])
    }

    @Test fun defaultsWhenTheBindingSetsNothingAndNoResponseFormat() = runTest {
        server.enqueue(MockResponse().setBody(ok))
        OpenAiCompatProvider(url(), "k", "m", style = OpenAiCompatProvider.ApiStyle.OPENAI, client = client).complete(ModelRequest("q"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0.2", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("500", body["max_completion_tokens"]!!.jsonPrimitive.content)
        assertEquals("m", body["model"]!!.jsonPrimitive.content)
        assertFalse("response_format" in body)
    }

    @Test fun nonSuccessIsTypedWithItsCode() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow"))
        val e = runCatching { OpenAiCompatProvider(url(), "k", "m", client = client).complete(ModelRequest("q")) }.exceptionOrNull()
        assertTrue(e is ProviderHttpException); assertEquals(429, (e as ProviderHttpException).code)
    }

    @Test fun redirectsAreNotFollowed() = runTest {
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/x").toString()))
            val e = runCatching { OpenAiCompatProvider(url(), "k", "m", client = client).complete(ModelRequest("q")) }.exceptionOrNull() as ProviderHttpException
            assertEquals(302, e.code); assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    /** Phase 5 T2 carry: a server that reports no usage still answers; the counts are just unknown. */
    @Test fun aReplyWithoutUsageHasNoTokenCounts() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        val r = OpenAiCompatProvider(url(), "k", "m", style = OpenAiCompatProvider.ApiStyle.OPENAI, client = client).complete(ModelRequest("q"))
        assertEquals("hi", r.text); assertEquals(null, r.promptTokens); assertEquals(null, r.completionTokens)
    }
}
