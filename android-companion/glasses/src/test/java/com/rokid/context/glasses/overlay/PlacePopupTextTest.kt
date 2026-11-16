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
}
