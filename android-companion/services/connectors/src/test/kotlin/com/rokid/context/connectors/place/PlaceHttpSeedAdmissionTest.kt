package com.rokid.context.connectors.place

import com.rokid.context.memory.place.SeedAdmission
import com.rokid.context.memory.place.SeedStopped
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A seed's request asks the admission (privacy, free Wi-Fi, data saver) before it opens a connection, every time; outside a seed nothing is asked. */
class PlaceHttpSeedAdmissionTest {
    @Test fun aRefusedAdmissionStopsTheRequestBeforeAnyConnectionIsOpened() {
        val opened = AtomicInteger()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { opened.incrementAndGet(); error("must not connect") })
        val e = assertFailsWith<SeedStopped> { runBlocking { withContext(SeedAdmission { "data saver" }) { http.get("https://a.example/x") } } }
        assertEquals("data saver", e.reason)
        assertEquals(0, opened.get())
    }

    @Test fun theAdmissionIsAskedForEveryRequestNotOnlyTheFirst() {
        val asked = AtomicInteger()
        val http = UrlConnectionPlaceHttp(minGapMs = 0, open = { error("refused before connecting") })
        runBlocking {
            withContext(SeedAdmission { if (asked.incrementAndGet() <= 2) null else "not on free Wi-Fi" }) {
                // the first two are admitted (they reach the connection factory, which throws); the third is refused by the admission itself
                repeat(2) { runCatching { http.get("https://a.example/x") } }
                assertFailsWith<SeedStopped> { http.get("https://a.example/x") }
            }
        }
        assertEquals(3, asked.get())
    }
}
