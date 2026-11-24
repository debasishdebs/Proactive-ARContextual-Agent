package com.rokid.context.model.provider

import com.rokid.context.ai.DataSaverBlockedException
import com.rokid.context.ai.PreConnectBlockedException
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.Skipped
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Codex data saver r2 P1-1: cancelling the coroutine aborts a blocking OkHttp call at the socket, and the transport asks the data saver right before the connect. */
class FencedCancelTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() { runCatching { server.shutdown() } } // a deliberately stalled response may keep its worker thread for a moment
    private val photo = ModelRequest("q", images = listOf(ModelImageInput("image/jpeg", "AAAA")))
    private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build() // a stalled server would block for two minutes
    private fun ollama() = OllamaProvider(server.url("/").toString().trimEnd('/'), "m", client)

    @Test fun cancellingTheCoroutineAbortsABlockedCallAtOnce() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val work = async(Dispatchers.IO) { ollama().complete(photo) }
        assertTrue("the request reached the server", server.takeRequest(10, TimeUnit.SECONDS) != null)
        val t0 = System.nanoTime()
        work.cancel()
        withTimeout(10_000) { work.join() }
        assertTrue("aborted in well under the read timeout", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 10)
        assertTrue(work.isCancelled)
    }

    @Test fun cancellingWhileTheResponseBodyIsStillStreamingAbortsTheRead() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(100_000)).setBodyDelay(60, TimeUnit.SECONDS).throttleBody(10, 10, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>()
        val work = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { started.complete(Unit); ollama().complete(photo) }
        started.await(); server.takeRequest(10, TimeUnit.SECONDS)
        val t0 = System.nanoTime()
        work.cancel()
        withTimeout(10_000) { work.join() }
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - t0) < 10)
    }

    @Test fun theGuardIsAskedRightBeforeTheConnectAndARefusalSendsNothing() {
        val r = runCatching {
            runBlocking { withContext(TransportGuard { throw PreConnectBlockedException("roaming", DataKind.PHOTO, Skipped.UPLOAD_QUEUED) }) { ollama().complete(photo) } }
        }
        assertTrue("was ${r.exceptionOrNull()}", r.exceptionOrNull() is DataSaverBlockedException)
        assertEquals("not a byte left", 0, server.requestCount)
    }

    @Test fun anAllowingGuardChangesNothing() {
        server.enqueue(MockResponse().setBody("""{"response":"ok"}"""))
        var asked = 0
        val reply = runBlocking { withContext(TransportGuard { asked++ }) { ollama().complete(photo) } }
        assertEquals("ok", reply.text); assertEquals(1, asked)
    }
}
