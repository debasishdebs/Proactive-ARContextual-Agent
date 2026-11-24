package com.rokid.context.connectors.place

import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlaceOfflineOnly
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Model privacy is asked before EVERY place request, not once per cell: a fetch started in Automatic stops sending the moment Offline only is set. */
class PlaceHttpOfflineOnlyTest {
    @Test fun offlineOnlyRefusesBeforeAnyConnectionIsOpened() {
        val opened = AtomicInteger()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { opened.incrementAndGet(); error("must not connect") }, privacy = { ModelPrivacy.OFFLINE_ONLY })
        assertFailsWith<PlaceOfflineOnly> { runBlocking { http.get("https://a.example/x") } }
        assertFailsWith<PlaceOfflineOnly> { runBlocking { http.postForm("https://a.example/x", mapOf("data" to "bbox")) } }
        assertEquals(0, opened.get())
    }

    @Test fun anUnreadableModeFailsClosed() {
        val opened = AtomicInteger()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { opened.incrementAndGet(); error("must not connect") }, privacy = { throw IllegalStateException("unreadable") })
        assertFailsWith<PlaceOfflineOnly> { runBlocking { http.get("https://a.example/x") } }
        assertEquals(0, opened.get())
    }

    @Test fun theModeIsReadForEveryRequestSoAFlipMidFetchStopsTheNextOne() {
        val asked = AtomicInteger()
        val opened = AtomicInteger()
        // Asked three times per request (before the host-gap wait, after it, and on the IO thread before the connection): the flip lands after the first request's two asks.
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { opened.incrementAndGet(); throw java.io.IOException("no network in a unit test") }, privacy = { if (asked.incrementAndGet() <= 3) ModelPrivacy.AUTO else ModelPrivacy.OFFLINE_ONLY })
        runBlocking {
            runCatching { http.get("https://a.example/x") } // admitted: reaches the connection factory
            assertFailsWith<PlaceOfflineOnly> { http.get("https://a.example/y") }
        }
        assertEquals(1, opened.get())
    }

    @Test fun aModeFlipWhileTheRequestIsQueuedForIoStopsItBeforeTheConnection() {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val queueing = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val mode = java.util.concurrent.atomic.AtomicReference(ModelPrivacy.AUTO)
        val opened = AtomicInteger()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { opened.incrementAndGet(); error("must not connect") }, privacy = { mode.get() }, io = queueing)
        runBlocking {
            val call = async { runCatching { http.get("https://a.example/x") } }
            while (queued.isEmpty()) kotlinx.coroutines.yield() // the request passed both checks and now waits for an IO thread
            mode.set(ModelPrivacy.OFFLINE_ONLY) // the user enables Offline only while it waits
            while (true) queued.poll()?.run() ?: break
            assertTrue(call.await().exceptionOrNull() is PlaceOfflineOnly)
        }
        assertEquals(0, opened.get())
    }

    @Test fun anOverpassMirrorLoopDoesNotTryTheOtherMirrorsOnARefusal() {
        val posts = AtomicInteger()
        val h = object : PlaceHttp {
            override suspend fun get(url: String): String = error("unused")
            override suspend fun postForm(url: String, form: Map<String, String>): String { posts.incrementAndGet(); throw PlaceOfflineOnly() }
        }
        val mirrors = OverpassMirrorSource(listOf(OverpassHttpSource(h, "https://m1.example/i"), OverpassHttpSource(h, "https://m2.example/i")), pause = {})
        val box = requireNotNull(Geohash5.bounds(Geohash5.encode(17.36, 78.47)))
        assertFailsWith<PlaceOfflineOnly> { runBlocking { mirrors.elements(box) } }
        assertFailsWith<PlaceOfflineOnly> { runBlocking { mirrors.elements(box) } } // and no "all mirrors failed" cooldown was recorded
        assertEquals(2, posts.get()) // one request per call, never one per mirror
    }
}
