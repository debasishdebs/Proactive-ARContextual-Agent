package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.zone.PauseZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression: ZonesScreenTest never ran the Canvas draw lambda (no frame is drawn under the compose test rule), so drawText's
 * "maxHeight(-34) must be >= minHeight(0)" crash on the real device went unseen. This drives the real drawGrid/drawZone with a real TextMeasurer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZonesDrawTest {
    private val density = Density(2f)
    private val tm = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
    private val charminar = PauseZone(id = "c", name = "Charminar", south = 17.3600, west = 78.4730, north = 17.3630, east = 78.4760, bufferM = 300, enabled = true)

    private fun draw(w: Int, h: Int, v: MapViewport, zones: List<PauseZone>) {
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(android.graphics.Canvas()), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat())) {
            drawGrid(v, tm)
            for (z in zones) drawZone(v, z, tm, active = true)
        }
    }

    @Test fun gridWithoutZonesOnPhoneAndTinyAndTallCanvases() {
        for ((w, h) in listOf(320 to 640, 1080 to 600, 60 to 20, 5 to 5, 200 to 2000)) {
            draw(w, h, MapViewport.default(w.toFloat(), h.toFloat()), emptyList())
            draw(w, h, MapViewport(17.3616, 78.4747, 20_000.0, w.toFloat(), h.toFloat()), emptyList())
        }
    }

    @Test fun zonesFitAndEdgeCases() {
        for ((w, h) in listOf(320 to 640, 1080 to 600, 60 to 20)) {
            draw(w, h, MapViewport.fit(listOf(MapViewport.ringOf(charminar)), w.toFloat(), h.toFloat()), listOf(charminar))
            // zone on the very edge / mostly off-screen: its name label lands past the canvas bottom-right
            draw(w, h, MapViewport(17.3600, 78.4760, 50_000.0, w.toFloat(), h.toFloat()), listOf(charminar))
            draw(w, h, MapViewport(17.2, 78.3, 50_000.0, w.toFloat(), h.toFloat()), listOf(charminar))
        }
    }
}
