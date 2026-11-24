package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.FakeZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.SeedLevel
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords
import com.rokid.context.phone.zone.ZoneChange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapZonesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<MapZonesViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private val ed = FakeZoneEditor()
    private fun vm() = MapZonesViewModel(ed, newId = { "zone-1" }, work = main.dispatcher).also { vms += it }
    private val rect = DrawnShape.Rect(17.2443, 78.6708, 17.2643, 78.6908)

    private fun MapZonesViewModel.toPauseSheet(name: String = "Home") {
        place(rect); next(); choose(SheetMode.PAUSE); setName(name)
    }

    @Test fun drawThenCancelMakesNoStoreCall() {
        val v = vm()
        v.beginDraw(); v.place(rect); v.next(); v.choose(SheetMode.PAUSE); v.setName("Home"); v.cancelDraw()
        assertTrue(ed.upserts.isEmpty()); assertTrue(ed.calls.isEmpty())
        assertNull(v.state.value.shape); assertEquals(SheetMode.NONE, v.state.value.sheet)
    }

    @Test fun saveMakesExactlyOneUpsertAndClosesOnOk() {
        val v = vm(); v.toPauseSheet()
        v.savePause()
        assertEquals(1, ed.upserts.size)
        val z = ed.upserts.single()
        assertEquals("zone-1", z.id); assertEquals("Home", z.name); assertEquals(300, z.bufferM)
        assertTrue(z.south <= 17.2443 && z.west <= 78.6708 && z.north >= 17.2643 && z.east >= 78.6908) // outward rounding never covers less
        assertEquals(SheetMode.NONE, v.state.value.sheet); assertNull(v.state.value.shape); assertEquals(1, v.state.value.savedTick)
    }

    @Test fun everyRefusalKeepsTheSheetOpenWithTheExistingWording() {
        for (c in listOf(ZoneChange.BLOCKED_INSIDE, ZoneChange.PROTECTED, ZoneChange.NOT_READY, ZoneChange.STORE_FAILED, ZoneChange.INVALID)) {
            val e = FakeZoneEditor(); e.next = c
            val v = MapZonesViewModel(e, newId = { "z" }, work = main.dispatcher).also { vms += it }
            v.toPauseSheet(); v.savePause()
            assertEquals("upsert once for $c", 1, e.upserts.size)
            assertEquals(SheetMode.PAUSE, v.state.value.sheet)
            assertEquals(ZoneWords.refusal(c, "replaced"), v.state.value.sheetError)
            assertNotNull(v.state.value.shape); assertFalse(v.state.value.saving); assertEquals(0, v.state.value.savedTick)
        }
    }

    @Test fun badNameOrBufferIsRefusedBeforeAnyStoreCall() {
        val v = vm(); v.toPauseSheet(name = " ")
        v.savePause(); assertTrue(ed.upserts.isEmpty()); assertNotNull(v.state.value.sheetError)
        v.setName("Home"); v.setBuffer("99999999"); v.savePause(); assertTrue(ed.upserts.isEmpty()); assertNotNull(v.state.value.sheetError)
        v.setBuffer("abc"); v.savePause(); assertTrue(ed.upserts.isEmpty())
    }

    @Test fun protectedModeDisablesPauseButNotSeed() {
        ed.snap.value = ed.snap.value.copy(protectedMode = true)
        val v = vm(); v.place(rect); v.next()
        v.choose(SheetMode.PAUSE)
        assertEquals(SheetMode.CHOOSE, v.state.value.sheet); assertEquals(ZoneWords.PROTECTED_BANNER, v.state.value.sheetError)
        v.choose(SheetMode.SEED); assertEquals(SheetMode.SEED, v.state.value.sheet); assertNull(v.state.value.sheetError)
        v.editPause("x"); assertEquals(ZoneWords.PROTECTED_BANNER, v.state.value.message)
        assertTrue(ed.upserts.isEmpty())
    }

    @Test fun editingReplacesTheSameZoneAndKeepsItsSwitchAndStaysStoredUntilOk() {
        val old = PauseZone("zone-9", "Camp", 12.0, 92.0, 12.5, 92.5, 500, enabled = false)
        ed.stored = listOf(old); ed.snap.value = ed.snap.value.copy(zones = listOf(old))
        val v = vm()
        v.editPause("zone-9")
        assertEquals("Camp", v.state.value.name); assertEquals("500", v.state.value.bufferText); assertEquals("zone-9", v.state.value.editingId)
        assertTrue(ed.upserts.isEmpty()) // opening an edit stores nothing
        v.reshape(ShapeEdit.move(v.state.value.shape!!, 0.1, 0.1)); v.next(); assertEquals(SheetMode.PAUSE, v.state.value.sheet)
        ed.next = ZoneChange.BLOCKED_INSIDE; v.savePause()
        assertEquals(old, ed.stored.single()); assertEquals(SheetMode.PAUSE, v.state.value.sheet)
        ed.next = ZoneChange.OK; v.savePause()
        assertEquals(2, ed.upserts.size); val z = ed.upserts.last()
        assertEquals("zone-9", z.id); assertFalse(z.enabled); assertEquals(500, z.bufferM)
        assertEquals(SheetMode.NONE, v.state.value.sheet)
    }

    @Test fun aCircleIsSavedAsTheBoxAroundIt() {
        val c = DrawnShape.Circle(27.3, 88.6, 800.0)
        val v = vm(); v.place(c); v.next(); v.choose(SheetMode.PAUSE); v.setName("Round"); v.savePause()
        val z = ed.upserts.single(); val b = CircleBox.of(27.3, 88.6, 800.0)!!
        assertTrue(z.south <= b[0] && z.west <= b[1] && z.north >= b[2] && z.east >= b[3])
    }

    @Test fun secondTapWhileSavingDoesNothingAndNewVmHasNoDraft() {
        val v = vm(); v.toPauseSheet(); v.savePause(); v.savePause()
        assertEquals(1, ed.upserts.size) // after OK the draft is gone, so a second tap has no shape
        assertNull(vm().state.value.shape)
    }

    @Test fun tooSmallOrBadShapeIsRefused() {
        val v = vm(); v.beginDraw(); v.place(null)
        assertNull(v.state.value.shape); assertTrue(v.state.value.messageIsError); assertFalse(v.state.value.drawing)
        v.place(rect); v.reshape(DrawnShape.Rect(Double.NaN, 0.0, 1.0, 1.0)); assertEquals(rect, v.state.value.shape)
    }

    @Test fun seedEstimateComesFromTheDrawnBox() {
        val v = vm(); assertEquals(SeedLevel.EMPTY, v.seedLevel())
        v.place(rect); assertEquals(SeedLevel.OK, v.seedLevel()); assertNotNull(v.seedBox())
        v.reshape(DrawnShape.Rect(-80.0, -170.0, 80.0, 170.0)); assertEquals(SeedLevel.TOO_LARGE, v.seedLevel())
    }

    @Test fun switchingKindKeepsTheArea() {
        val v = vm(); v.place(rect); v.setKind(ShapeKind.CIRCLE)
        val c = v.state.value.shape as DrawnShape.Circle
        assertTrue(c.radiusM > 100.0); v.setKind(ShapeKind.RECT); assertTrue(v.state.value.shape is DrawnShape.Rect)
    }

    @Test fun pauseLayerStaysOnWhileLocked() {
        val v = vm(); v.toggleLayer(MapLayer.PAUSE, true); assertTrue(MapLayer.PAUSE in v.state.value.layers)
        v.toggleLayer(MapLayer.PAUSE, false); assertFalse(MapLayer.PAUSE in v.state.value.layers)
    }
}
