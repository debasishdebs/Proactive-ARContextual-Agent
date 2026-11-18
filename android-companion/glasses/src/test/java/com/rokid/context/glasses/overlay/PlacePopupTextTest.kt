package com.rokid.context.glasses.overlay

import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo
import com.rokid.context.core.model.Verbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Place Guide PG3: what a place card becomes as a minimised pop-up (the HUD path is covered by HudPresenterTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlacePopupTextTest {
    private val card = Overlay.Nudge("Charminar: A monument.", PlaceCardInfo("Charminar", "A monument.", "Wikipedia \u00b7 fetched 12 Nov"))

    @Test fun aPlaceCardIsThreeLines() = assertEquals("Charminar\nA monument.\nWikipedia \u00b7 fetched 12 Nov", PopupOverlay.textFor(card, Verbosity.MEDIUM))
    @Test fun silentShowsNoPlaceCard() = assertNull(PopupOverlay.textFor(card, Verbosity.SILENT))
    @Test fun aPlainNudgeKeepsItsText() = assertEquals("Leave now", PopupOverlay.textFor(Overlay.Nudge("Leave now"), Verbosity.MAX))

    // v3.1 r6 P1-1/2
    private fun sg(maxShowMs: Long? = null, hideAtMs: Long? = null) = Overlay.Nudge("a: b", PlaceCardInfo("a", "b", "s", maxShowMs = maxShowMs, hideAtMs = hideAtMs))
    @Test fun aPlaceCardPopupUsesItsCapNotTheFlatTwelveSeconds() { assertEquals(5_000L, PopupOverlay.showMs(sg(maxShowMs = 5_000), 0, 8_000)); assertEquals(12_000L, PopupOverlay.showMs(sg(), 0, 8_000)) }
    @Test fun aZeroOrNegativeCapDrawsNothing() { assertEquals(0L, PopupOverlay.showMs(sg(maxShowMs = 0), 0, 8_000)); assertEquals(0L, PopupOverlay.showMs(sg(maxShowMs = -5), 0, 8_000)) }
    @Test fun aDelayedFirstDrawCannotOutliveTheAbsoluteBoundary() {
        val c = sg(maxShowMs = 7_000, hideAtMs = 1_000_000)   // published 7 s before the boundary
        assertEquals(7_000L, PopupOverlay.showMs(c, 993_000, 8_000))
        assertEquals("drawn 5 s late: only 2 s left, not 7", 2_000L, PopupOverlay.showMs(c, 998_000, 8_000))
        assertEquals("at the boundary: nothing", 0L, PopupOverlay.showMs(c, 1_000_000, 8_000)); assertEquals("after it: nothing", 0L, PopupOverlay.showMs(c, 1_050_000, 8_000))
    }
    @Test fun aPlainNudgeKeepsTheUserPopupTime() = assertEquals(8_000L, PopupOverlay.showMs(Overlay.Nudge("Leave now"), 0, 8_000))
}
