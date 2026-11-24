package com.rokid.context.work.frames.place

import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5b: the quoted-sign match against the user's own pins. Synthetic names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceTextMatchTest {
    private val T0 = 10_000_000L
    private var now = T0
    private var zone = false
    private var moved = false
    private var capturedCalls = 0
    private var capturedAt = T0 - 30_000
    private var pins: List<PlaceEntity> = listOf(pin(1, "Kestrel Souvenir House", 10.0))
    private val cards = mutableListOf<PlaceCard>()
    private val events = mutableListOf<PlaceHistoryEvent>()
    private val logs = mutableListOf<String>()
    private val flight = PlaceFlight()

    private fun pin(n: Int, name: String, lat: Double) = PlaceEntity("user:00000000-0000-0000-0000-%012d".format(n), null, name, "shop", lat, 20.0, 30, "note", "user", "user", 0, false, true, 0, 0)
    private fun good() { flight.good = PlaceFlight.GoodFix(10.0, 20.0, 10f, now, "s1", now, 0L) }

    private val store = object : PlaceFrameStore {
        override suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame? = null
        override suspend fun tombstoned(frameId: String) = false
        override suspend fun movedSince(sinceReceivedMs: Long) = moved
    }

    private fun matcher(scope: TestScope) = PlaceTextMatch(
        scope = scope, flight = flight, store = store, captured = { ids -> capturedCalls++; ids.associateWith { capturedAt } }, pins = { pins },
        enabled = { true }, textOn = { true }, zoneBlocked = { zone }, clock = { now }, elapsed = { now }, onCard = { cards += it }, history = { events += it }, log = { logs += it },
    )
    private fun TestScope.run(vararg c: String) { matcher(this).onCaptions(c.mapIndexed { i, t -> FrameCaption("f$i", t) }); runCurrent() }

    @Test fun quotedSign_onePinNear_card() = runTest {
        good(); run("A board saying \"Kestrel Souvenir House\" over the door")
        assertEquals(1, cards.size); assertEquals("Sign read · your pin", cards[0].sourceLabel); assertEquals(PlaceConfidence.PIN, cards[0].confidence)
        assertEquals(HistoryKind.TEXT, events.single().kind)
        assertTrue(logs.single() == "place text match frames=1 quoted=1 hits=1 result=card")
        // the same sign again within the visit: no second card
        run("\"Kestrel Souvenir House\""); assertEquals(1, cards.size)
    }

    @Test fun quotedSign_late_historyOnly() = runTest {
        good(); capturedAt = T0 - 5 * 60_000
        run("\"Kestrel Souvenir House\"")
        assertTrue(cards.isEmpty()); assertEquals(CardDelivery.NOT_SENT_LATE, events.single().delivery); assertTrue(logs.single().endsWith("result=history"))
        events.clear(); logs.clear(); capturedAt = T0 - 30_000; moved = true
        run("\"Kestrel Souvenir House\"")
        assertTrue("the wearer moved since the photo: a late row", cards.isEmpty() && events.single().delivery == CardDelivery.NOT_SENT_LATE)
    }

    @Test fun unquotedObjectWord_noMatch() = runTest {
        good(); pins = listOf(pin(1, "Bronze Gallery", 10.0))
        run("a bronze statue next to a gallery wall")
        assertTrue(cards.isEmpty() && events.isEmpty()); assertTrue(logs.single().endsWith("quoted=0 hits=0 result=none"))
    }

    @Test fun noRecentFix_noMatch() = runTest {
        run("\"Kestrel Souvenir House\"")
        assertTrue(cards.isEmpty() && events.isEmpty()); assertTrue(logs.single().endsWith("result=no_fix"))
        good(); now += 31 * 60_000L; capturedAt = now - 10_000; logs.clear()
        run("\"Kestrel Souvenir House\""); assertTrue(logs.single().endsWith("result=no_fix"))
    }

    @Test fun twoPins_ambiguous() = runTest {
        good(); pins = listOf(pin(1, "Kestrel Souvenir House", 10.0), pin(2, "Kestrel Cafe", 10.0001))
        run("\"Kestrel\"")
        assertTrue(cards.isEmpty() && events.isEmpty()); assertTrue(logs.single().endsWith("hits=2 result=ambiguous"))
    }

    @Test fun pinFarFromTheLastFix_isNotACandidate() = runTest {
        good(); pins = listOf(pin(1, "Kestrel Souvenir House", 10.5))
        run("\"Kestrel Souvenir House\""); assertTrue(cards.isEmpty()); assertTrue(logs.single().endsWith("hits=0 result=none"))
    }

    @Test fun zone_readsNothing() = runTest {
        good(); zone = true
        run("\"Kestrel Souvenir House\"")
        assertEquals(0, capturedCalls); assertTrue(cards.isEmpty() && events.isEmpty() && logs.isEmpty())
    }

    /** P2-6: the sign path claims the pin through the SAME reservation as the sampler's cards: while another attempt holds it, no second card; afterwards the claim is released. */
    @Test fun reservationHeldByAnotherAttempt_noSecondCard_andReleasedAfterTheCard() = runTest {
        good(); val id = pins.single().id
        assertTrue(flight.reserve(listOf(id), now)) // e.g. the sampler's pin-first card for the same pin is being published
        run("\"Kestrel Souvenir House\""); assertTrue(cards.isEmpty() && events.isEmpty()); assertTrue(logs.single().endsWith("result=none"))
        flight.unreserve(listOf(id)); logs.clear()
        run("\"Kestrel Souvenir House\""); assertEquals(1, cards.size)
        assertTrue("the claim is released (a different path may reserve other pins) and the visit now holds this one", flight.reserve(listOf("user:other"), now) && !flight.reserve(listOf(id), now))
    }

    @Test fun twoOverlappingMatchers_exactlyOneCard() = runTest {
        good()
        val a = matcher(this); val b = matcher(this)
        val cap = listOf(FrameCaption("f0", "\"Kestrel Souvenir House\""))
        a.onCaptions(cap); b.onCaptions(cap); runCurrent()
        assertEquals(1, cards.size)
    }

    @Test fun replay_captionStage_quotedOnly() {
        assertEquals(listOf("Kestrel Souvenir House"), TextMatch.quotedSpans("sign \"Kestrel Souvenir House\" here"))
        assertEquals(listOf("Blue Door"), TextMatch.quotedSpans("sign “Blue Door” here"))
        assertTrue(TextMatch.quotedSpans("a kestrel souvenir house statue").isEmpty())
        assertTrue(TextMatch.quotedSpans("\"a\"").isEmpty())
        assertTrue(TextMatch.names("Kestrel Souvenir House", "KESTREL souvenir-house"))
        assertTrue(TextMatch.names("Kestrel Souvenir House", "Souvenir"))
        assertFalse(TextMatch.names("Bronze Gallery", "gallery"))
    }
}
