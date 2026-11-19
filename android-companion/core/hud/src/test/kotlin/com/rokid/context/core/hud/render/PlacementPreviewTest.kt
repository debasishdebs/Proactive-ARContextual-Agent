package com.rokid.context.core.hud.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo
import com.rokid.context.core.model.PlaceSide
import com.rokid.context.core.model.StateFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real HudRoot at the glasses' 480x640 px / 240 dpi (240x320 dp) with native graphics and writes PNGs to build/placement-preview (a preview aid, not an assertion of optics). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h320dp-hdpi")
class PlacementPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = 1_793_000_000_000L
    private val line = "A twelfth-century stepwell on the old town square, rebuilt in 1890 after a fire and now used as a small public library and archive."

    private fun model(info: PlaceCardInfo): HudModel {
        val overlay = Overlay.Nudge("${info.title}: ${info.line}", info)
        return HudModel(
            view = HudView(HudPage.GLANCE, overlay),
            frame = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, glassesBatteryPct = 82, phoneLinked = true, aiHealth = Health.Ok, queueDepth = 0, overlay = overlay),
            settings = HudSettings(), connection = HudConnection.LIVE, batteryPct = 82, paused = false, nextCaptureAtMs = now + 252_000, nowMs = now,
            nav = HudNav(page = HudPage.GLANCE), confirmHighlightYes = true,
        )
    }

    private fun render(name: String, info: PlaceCardInfo) {
        compose.setContent { HudRoot(model(info)) }
        compose.waitForIdle()
        val v = compose.activity.findViewById<android.view.View>(android.R.id.content)
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(480, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(640, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 480, 640)
        val bmp = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/placement-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(bmp.width == 480 && bmp.height == 640) { "unexpected size ${bmp.width}x${bmp.height}" }
    }

    private fun place(side: PlaceSide, nearby: Boolean) = PlaceCardInfo("Chowmahalla Palace", line.take(140), if (nearby) "nearby \u00b7 not checked" else "Wikipedia \u00b7 fetched 12 Nov", nearby = nearby, side = PlaceSide.toWire(side))
    private fun suggestion(side: PlaceSide) = PlaceCardInfo("Pick up milk", line.take(140), "Suggestion \u00b7 Wikipedia \u00b7 fetched 12 Nov", nearby = false, side = PlaceSide.toWire(side))

    @Test fun placeCentre() = render("place-centre", place(PlaceSide.CENTRE, false))
    @Test fun placeLeft() = render("place-left", place(PlaceSide.LEFT, false))
    @Test fun placeRight() = render("place-right", place(PlaceSide.RIGHT, false))
    @Test fun placeNearbyLeft() = render("place-nearby-left", place(PlaceSide.LEFT, true))
    @Test fun suggestionCentre() = render("suggestion-centre", suggestion(PlaceSide.CENTRE))
    @Test fun suggestionLeft() = render("suggestion-left", suggestion(PlaceSide.LEFT))
    @Test fun suggestionRight() = render("suggestion-right", suggestion(PlaceSide.RIGHT))
}
