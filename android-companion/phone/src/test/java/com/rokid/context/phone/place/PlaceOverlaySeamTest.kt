package com.rokid.context.phone.place

import com.rokid.context.core.model.Overlay
import com.rokid.context.phone.hub.Hub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PG2 seam: `pickOverlay` takes `place ?: nudge`; no protocol change (a place overlay is just an [Overlay] the next package maps). */
class PlaceOverlaySeamTest {
    private val nudge = Overlay.Nudge("n")
    private val place = Overlay.Nudge("p") // stands in for the PG3 place overlay

    @Test fun aPlaceCardBeatsANudge() = assertEquals(place, Hub.pickOverlay(null, null, nudge, place = place))
    @Test fun withoutAPlaceTheNudgeShows() = assertEquals(nudge, Hub.pickOverlay(null, null, nudge))
    @Test fun anAnswerOrToastStillBeatsThePlaceCard() {
        val toast = Overlay.Toast("t")
        assertEquals(toast, Hub.pickOverlay(null, toast, nudge, place = place))
    }
    @Test fun nothingIsNothing() = assertNull(Hub.pickOverlay(null, null, null, place = null))

    @Test fun theFixIsTheZoneStreamsWhenFreshElseOneOnDemandElseNone() = runBlocking {
        var asked = 0
        val provider = PlaceFixProvider(controller = { null }, elapsed = { 1_000L }, oneShot = { asked++; null })
        assertNull(provider.fresh()); assertEquals(1, asked)
    }
}
