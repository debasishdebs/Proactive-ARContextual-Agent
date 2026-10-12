package com.rokid.context.model

import com.rokid.context.model.provider.OllamaProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OllamaProviderTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private fun url() = server.url("/").toString().trimEnd('/')

    @Test fun formatOptionsAndImagesAreSentAndCountsRead() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"{}","prompt_eval_count":30,"eval_count":4}"""))
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val r = OllamaProvider(url(), "llava").complete(
            ModelRequest("q", images = listOf(ModelImageInput("image/jpeg", "AAAA")), temperature = 0.1, maxTokens = 99, format = WireFormat.OllamaSchema(schema)),
        )
        assertEquals("{}", r.text); assertEquals(30, r.promptTokens); assertEquals(4, r.completionTokens)
        val req = server.takeRequest()
        assertEquals("/api/generate", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(schema, body["format"])
        assertEquals("AAAA", body["images"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("0.1", body["options"]!!.jsonObject["temperature"]!!.jsonPrimitive.content)
        assertEquals("99", body["options"]!!.jsonObject["num_predict"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
    }

    @Test fun plainRequestHasNoFormatOrImages() = runTest {
        server.enqueue(MockResponse().setBody("""{"response":"hi"}"""))
        assertEquals("hi", OllamaProvider(url(), "m").generate("q"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("format" in body); assertFalse("images" in body)
    }
}
