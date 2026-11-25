package com.rokid.context.model.provider

import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canvas v2.1 P3: the optional seed reaches the Ollama wire body when set and is absent otherwise (every live caller leaves it null). */
class SeedForwardTest {
    private val server = MockWebServer().apply { start() }
    @After fun close() { server.shutdown() }

    @Test fun ollamaForwardsSeedOnlyWhenSet() = runBlocking {
        val p = OllamaProvider("http://127.0.0.1:${server.port}", "m")
        server.enqueue(MockResponse().setBody("""{"response":"ok","done":true}"""))
        p.complete(ModelRequest("q", seed = 7))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"seed\":7"))
        server.enqueue(MockResponse().setBody("""{"response":"ok","done":true}"""))
        p.complete(ModelRequest("q"))
        assertFalse(server.takeRequest().body.readUtf8().contains("seed"))
    }
}
