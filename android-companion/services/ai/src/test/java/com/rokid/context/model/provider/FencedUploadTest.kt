package com.rokid.context.model.provider

import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneFenced
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Codex r5 P1-1: a photo upload of a Place Guide attempt is admitted at the last step before execute() and cancelled in flight by a trip. */
class FencedUploadTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private fun url() = server.url("/").toString().trimEnd('/')
    private val photo = ModelRequest("q", images = listOf(ModelImageInput("image/jpeg", "AAAA")))
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun openAi(fence: ZoneFence, hook: () -> Unit = {}) = OpenAiCompatProvider(url(), "", "d", style = OpenAiCompatProvider.ApiStyle.OPENAI, client = client, beforeAdmit = hook)
    private fun ollama(hook: () -> Unit = {}) = OllamaProvider(url(), "m", client, hook)

    @Test fun aTripDuringPayloadSerialisationSendsNothing_openAi() {
        val fence = ZoneFence()
        val r = runCatching { runBlocking { withContext(fence.ticket()) { openAi(fence) { fence.trip() }.complete(photo) } } }
        assertTrue("was ${r.exceptionOrNull()}", r.exceptionOrNull() is ZoneFenced)
        assertEquals(0, server.requestCount)
    }

    @Test fun aTripDuringPayloadSerialisationSendsNothing_ollama() {
        val fence = ZoneFence()
        val r = runCatching { runBlocking { withContext(fence.ticket()) { ollama { fence.trip() }.complete(photo) } } }
        assertTrue("was ${r.exceptionOrNull()}", r.exceptionOrNull() is ZoneFenced)
        assertEquals(0, server.requestCount)
    }

    @Test fun aTripWhileTheCallIsBlockedCancelsIt_openAi() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fence = ZoneFence()
        Thread { server.takeRequest(10, TimeUnit.SECONDS); fence.trip() }.start()
        val t0 = System.nanoTime()
        val r = runCatching { runBlocking { withContext(fence.ticket()) { openAi(fence).complete(photo) } } }
        assertTrue("was ${r.exceptionOrNull()}", r.exceptionOrNull() is ZoneFenced)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 20)
    }

    @Test fun aTripWhileTheCallIsBlockedCancelsIt_ollama() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val fence = ZoneFence()
        Thread { server.takeRequest(10, TimeUnit.SECONDS); fence.trip() }.start()
        val r = runCatching { runBlocking { withContext(fence.ticket()) { ollama().complete(photo) } } }
        assertTrue("was ${r.exceptionOrNull()}", r.exceptionOrNull() is ZoneFenced)
    }

    @Test fun unfencedCallsAreUnchanged() {
        server.enqueue(MockResponse().setBody("""{"response":"hi"}"""))
        assertEquals("hi", runBlocking { ollama { }.generate("q") })
    }
}
