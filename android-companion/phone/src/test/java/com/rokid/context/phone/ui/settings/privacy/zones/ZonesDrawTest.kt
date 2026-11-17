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
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
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

    private val jarawa = PauseZone(id = "j", name = "Jarawa reserve (Andaman Trunk Road)", south = 11.5, west = 92.4, north = 12.0, east = 92.7, bufferM = 5000, enabled = true)

    private fun scope(w: Int, h: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) =
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(android.graphics.Canvas()), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), block)

    @Test fun aLongZoneNameNearTheRightEdgeSlidesLeftAndIsNeverOneCharacterWide() {
        val w = 1080; val h = 600
        var right: Float? = null; var lines = 99
        scope(w, h) {
            val style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            // label anchored 6 px from the right edge: used to be laid out in a 6 px wide column
            right = drawLabel(tm, jarawa.name, androidx.compose.ui.geometry.Offset(w - 6f, 20f), style, shift = true)
            lines = tm.measure(jarawa.name, style, softWrap = true, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (160 * 2))).lineCount
        }
        assertTrue("label stays on the canvas", right!! <= w + 0.5f)
        assertTrue("label wraps into few lines, not a one-character column", lines <= 4)
        draw(w, h, MapViewport(11.75, 92.7, 200_000.0, w.toFloat(), h.toFloat()), listOf(jarawa))
        draw(320, 640, MapViewport.default(320f, 640f), listOf(jarawa))
    }

    @Test fun longitudeTickLabelsAreSkippedWhenTheyWouldOverlapWhenZoomedIn() {
        val w = 1080; val h = 600
        var kept = emptyList<LonTick>(); var all = 0
        for (span in listOf(60.0, 400.0, 2_000.0, 20_000.0)) {
            val v = MapViewport(17.3616, 78.4747, span, w.toFloat(), h.toFloat())
            scope(w, h) {
                val ticks = lonTicks(v, tm, androidx.compose.ui.text.TextStyle(fontSize = 10.sp)); all += ticks.size
                kept = ticks.filter { it.text != null }
                drawGrid(v, tm)
            }
            for ((a, b) in kept.zipWithNext()) assertTrue("labels ${a.left}..${a.right} and ${b.left} overlap (span $span)", a.right <= b.left)
            assertTrue(kept.isNotEmpty())
        }
    }
}
