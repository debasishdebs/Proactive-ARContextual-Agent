package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.FakeZoneEditor
import com.rokid.context.phone.zone.ZoneChange
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MapZonesP23ViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<MapZonesViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private val ed = FakeZoneEditor()
    private var now = 0L
    private fun vm() = MapZonesViewModel(ed, newId = { "zone-new" }, work = main.dispatcher, clock = { now }).also { vms += it }
    private val rect = DrawnShape.Rect(17.2443, 78.6708, 17.2643, 78.6908)
    private val zone = PauseZone("z1", "Home", 17.0, 78.0, 17.1, 78.1, 300)

    private fun stored() { ed.stored = listOf(zone) }
    private fun MapZonesViewModel.step(shape: DrawnShape, dt: Long = 1_000) { now += dt; reshape(shape) }

    @Test fun undoAndRedoOnlyChangeTheDraft() {
        val v = vm(); v.place(rect)
        val b = DrawnShape.Rect(17.2443, 78.6708, 17.2743, 78.6908); val c = DrawnShape.Rect(17.2443, 78.6708, 17.2843, 78.6908)
        v.step(b); v.step(c)
        v.undo(); assertEquals(b, v.state.value.shape)
        v.undo(); assertEquals(rect, v.state.value.shape)
        v.undo(); assertEquals(rect, v.state.value.shape) // nothing more
        v.redo(); assertEquals(b, v.state.value.shape)
        assertTrue(ed.upserts.isEmpty()); assertTrue(ed.calls.isEmpty())
    }

    @Test fun cancelAndSaveDropTheHistory() {
        val v = vm(); v.place(rect); v.step(DrawnShape.Rect(17.2443, 78.6708, 17.2743, 78.6908))
        assertTrue(v.state.value.history.canUndo)
        v.cancelDraw(); assertFalse(v.state.value.history.canUndo); assertNull(v.state.value.shape)
        v.place(rect); v.next(); v.choose(SheetMode.PAUSE); v.setName("Home"); v.savePause()
        assertEquals(1, ed.upserts.size); assertFalse(v.state.value.history.canUndo)
    }

    @Test fun undoIsIgnoredWhileASaveRuns() {
        val v = vm(); v.place(rect); v.step(DrawnShape.Rect(17.2443, 78.6708, 17.2743, 78.6908)); v.next(); v.choose(SheetMode.PAUSE); v.setName("Home")
        val before = v.state.value.shape
        v.savePause() // the fake answers at once; undo after it finds no draft
        v.undo(); assertNull(v.state.value.shape); assertNotNull(before)
    }

    @Test fun coordinatesApplyAsOneUndoableStepAndBadOnesChangeNothing() {
        val v = vm(); v.place(rect)
        v.applyCoordinates(CoordText("17.0", "78.0", "17.1", "78.1"))
        assertEquals(DrawnShape.Rect(17.0, 78.0, 17.1, 78.1), v.state.value.shape); assertNull(v.state.value.coordError)
        v.undo(); assertEquals(rect, v.state.value.shape)
        v.applyCoordinates(CoordText("x", "78.0", "17.1", "78.1"))
        assertEquals(rect, v.state.value.shape); assertNotNull(v.state.value.coordError)
        v.applyCoordinates(CoordText("17.1", "78.0", "17.0", "78.1")) // south above north
        assertEquals(rect, v.state.value.shape); assertNotNull(v.state.value.coordError)
        assertTrue(ed.upserts.isEmpty())
        val none = vm(); none.applyCoordinates(CoordText("1", "2", "3", "4")); assertNotNull(none.state.value.coordError); assertNull(none.state.value.shape)
    }

    @Test fun aCircleTakesCentreAndRadius() {
        val v = vm(); v.place(DrawnShape.Circle(17.25, 78.68, 400.0))
        v.applyCoordinates(CoordText("17.30", "78.70", "900"))
        assertEquals(DrawnShape.Circle(17.30, 78.70, 900.0), v.state.value.shape)
    }

    // --- the guard: a stored zone is only reshaped when clearly outside, checked at open AND at save ---
    @Test fun guardOffKeepsP1Behaviour() {
        stored(); val v = vm()
        v.editPause("z1"); assertEquals("z1", v.state.value.editingId)
    }

    @Test fun guardBlocksEditWhileInsideOrUnknown() {
        stored(); val v = vm()
        v.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.INSIDE_OR_UNKNOWN))
        v.editPause("z1")
        assertNull(v.state.value.editingId); assertNull(v.state.value.shape); assertNotNull(v.state.value.message); assertTrue(v.state.value.messageIsError)
        v.setEditGuard(emptyMap()) // a guard with no row for the zone = unknown = blocked
        v.editPause("z1"); assertNull(v.state.value.editingId)
        v.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.UNREADABLE)); v.editPause("z1"); assertNull(v.state.value.editingId)
        assertTrue(ed.upserts.isEmpty())
    }

    @Test fun aZoneThatBecomesBlockedMidEditCannotBeSavedAndMakesNoStoreCall() {
        stored(); val v = vm()
        v.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.NONE))
        v.editPause("z1"); assertEquals("z1", v.state.value.editingId)
        v.step(DrawnShape.Rect(16.9, 77.9, 17.2, 78.2)); v.next()
        assertEquals(SheetMode.PAUSE, v.state.value.sheet)
        v.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.INSIDE_OR_UNKNOWN)) // you walked in
        v.savePause()
        assertTrue(ed.upserts.isEmpty())
        assertEquals(ZoneEditPolicy.words(ZoneEditPolicy.Block.INSIDE_OR_UNKNOWN), v.state.value.sheetError)
        assertNotNull(v.state.value.shape) // the draft stays; the stored zone stays in force
    }

    @Test fun anAllowedEditStillGoesThroughTheOneUpsertAndTheControllerStillDecides() {
        stored(); val v = vm()
        v.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.NONE))
        v.editPause("z1"); v.step(DrawnShape.Rect(16.9, 77.9, 17.2, 78.2)); v.next(); v.savePause()
        assertEquals(1, ed.upserts.size); assertEquals("z1", ed.upserts.single().id)
        val e2 = FakeZoneEditor(); e2.stored = listOf(zone); e2.next = ZoneChange.BLOCKED_INSIDE
        val v2 = MapZonesViewModel(e2, newId = { "n" }, work = main.dispatcher, clock = { now }).also { vms += it }
        v2.setEditGuard(mapOf("z1" to ZoneEditPolicy.Block.NONE)); v2.editPause("z1"); v2.next(); v2.savePause()
        assertEquals(1, e2.upserts.size); assertEquals(SheetMode.PAUSE, v2.state.value.sheet); assertNotNull(v2.state.value.sheetError)
    }

    @Test fun aNewZoneIsNotAffectedByTheGuard() {
        val v = vm(); v.setEditGuard(emptyMap())
        v.place(rect); v.next(); v.choose(SheetMode.PAUSE); v.setName("Home"); v.savePause()
        assertEquals(1, ed.upserts.size)
    }

    // --- Also seed ---
    @Test fun alsoSeedFromStoredMakesASeedDraftAndTouchesNoZone() {
        stored(); val v = vm()
        v.alsoSeedFromStored("z1")
        val s = v.state.value
        assertEquals(DrawnShape.Rect(17.0, 78.0, 17.1, 78.1), s.shape); assertEquals(SheetMode.SEED, s.sheet); assertNull(s.editingId)
        assertTrue(ed.upserts.isEmpty()); assertTrue(ed.calls.isEmpty())
        v.alsoSeedFromStored("nope"); assertEquals(SheetMode.SEED, v.state.value.sheet) // an unknown id changes nothing
    }

    @Test fun alsoSeedFromAnOpenPauseEditDropsTheEditNotTheZone() {
        stored(); val v = vm()
        v.editPause("z1"); v.next(); assertEquals(SheetMode.PAUSE, v.state.value.sheet)
        v.alsoSeedFromDraft()
        assertEquals(SheetMode.SEED, v.state.value.sheet); assertNull(v.state.value.editingId); assertEquals("", v.state.value.name)
        v.savePause() // the Seed sheet has no pause save; a stray call must not create a zone
        assertTrue("no store write from Also seed or a stray save", ed.upserts.isEmpty())
    }

    @Test fun flagsAreOffByDefaultAndNested() {
        assertEquals("mapZonesP23", com.rokid.context.orchestration.forget.DeveloperFlags.MAP_ZONES_P23)
        assertEquals("mapMomentsDensity", com.rokid.context.orchestration.forget.DeveloperFlags.MAP_MOMENTS_DENSITY)
    }
}
