package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertEquals
import org.junit.Test

class MapTapTest {
    private val all = MapLayer.values().toSet()
    private val v = MapViewport(27.3, 88.6, 2_000.0, 1000f, 1600f)
    private val big = MapItem("p", ItemType.PAUSE, doubleArrayOf(27.29, 88.59, 27.31, 88.62))
    private val inner = MapItem("s", ItemType.SEED, doubleArrayOf(27.295, 88.595, 27.305, 88.61))
    private val pin = MapItem("pin", ItemType.PIN, doubleArrayOf(27.4, 88.7, 27.4, 88.7))

    @Test fun pauseBeatsSeedAndPinsAreChips() {
        val c = v.toPx(27.3, 88.6)
        assertEquals(TapTarget.Item(ItemType.PAUSE, "p"), MapTap.at(listOf(big, inner), v, c.x, c.y, 16f, 48f, all))
        val p = v.toPx(27.4, 88.7)
        assertEquals(TapTarget.Item(ItemType.PIN, "pin"), MapTap.at(listOf(big, pin), v, p.x + 10f, p.y - 10f, 16f, 48f, all))
        assertEquals(TapTarget.None, MapTap.at(listOf(big), v, 0f, 0f, 16f, 48f, all))
    }

    @Test fun hiddenLayerAndBadInputAreIgnored() {
        val c = v.toPx(27.3, 88.6)
        assertEquals(TapTarget.None, MapTap.at(listOf(big), v, c.x, c.y, 16f, 48f, all - MapLayer.PAUSE))
        assertEquals(TapTarget.None, MapTap.at(listOf(big), v, Float.NaN, c.y, 16f, 48f, all))
        assertEquals(TapTarget.None, MapTap.at(listOf(big), v, c.x, c.y, 16f, 0f, all))
    }

    @Test fun mergedChipsReportACluster() {
        val far = MapViewport(27.3, 88.6, 30.0, 400f, 800f)
        val a = MapItem("a", ItemType.PIN, doubleArrayOf(27.30, 88.60, 27.30, 88.60)); val b = MapItem("b", ItemType.PIN, doubleArrayOf(27.301, 88.601, 27.301, 88.601))
        val p = far.toPx(27.3005, 88.6005)
        assertEquals(TapTarget.Cluster(listOf("a", "b")), MapTap.at(listOf(a, b), far, p.x, p.y, 16f, 48f, all))
    }
}
