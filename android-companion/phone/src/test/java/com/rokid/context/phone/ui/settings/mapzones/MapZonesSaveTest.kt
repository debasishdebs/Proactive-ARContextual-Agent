package com.rokid.context.phone.ui.settings.mapzones

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.privacy.zones.FakeZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneEditor
import com.rokid.context.phone.ui.settings.privacy.zones.ZoneWords
import com.rokid.context.phone.zone.ZoneChange
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A pause save in flight: navigation is blocked, the answer belongs to the submitted draft, and unreadable saved zones switch saving off live. */
class MapZonesSaveTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<MapZonesViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }
    private val rect = DrawnShape.Rect(17.2443, 78.6708, 17.2643, 78.6908)

    /** An editor whose upsert waits for [gate], so a save can be held in flight. */
    private class GateEditor(val inner: FakeZoneEditor) : ZoneEditor by inner {
        val gate = CompletableDeferred<Unit>()
        override suspend fun upsert(zone: PauseZone): ZoneChange { gate.await(); return inner.upsert(zone) }
    }

    private fun gated(next: ZoneChange = ZoneChange.OK): Pair<MapZonesViewModel, GateEditor> {
        val inner = FakeZoneEditor(); inner.next = next
        val g = GateEditor(inner)
        return MapZonesViewModel(g, newId = { "zone-1" }, work = main.dispatcher).also { vms += it } to g
    }

    private fun MapZonesViewModel.toPauseSheet() { place(rect); next(); choose(SheetMode.PAUSE); setName("Home") }

    @Test fun navigationAndEditsDoNothingWhileASaveIsInFlight() {
        val (v, g) = gated(); v.toPauseSheet(); v.savePause()
        assertTrue(v.state.value.saving)
        val before = v.state.value
        v.cancelDraw(); v.back(); v.place(null); v.place(DrawnShape.Rect(1.0, 1.0, 1.2, 1.2)); v.beginDraw(); v.setName("x"); v.setBuffer("5"); v.next(); v.setKind(ShapeKind.CIRCLE)
        v.reshape(ShapeEdit.move(rect, 0.1, 0.1)); v.editPause("a"); v.choose(SheetMode.SEED)
        assertEquals(before, v.state.value)
        v.savePause() // a second tap is ignored
        g.gate.complete(Unit)
        assertEquals(1, g.inner.upserts.size)
        assertFalse(v.state.value.saving); assertNull(v.state.value.shape)
    }

    @Test fun aRefusalArrivesOnTheSubmittedSheetEvenAfterAnAttemptToLeave() {
        val (v, g) = gated(ZoneChange.BLOCKED_INSIDE); v.toPauseSheet(); v.savePause(); v.cancelDraw(); v.back()
        g.gate.complete(Unit)
        assertEquals(SheetMode.PAUSE, v.state.value.sheet); assertNotNull(v.state.value.shape)
        assertEquals(ZoneWords.refusal(ZoneChange.BLOCKED_INSIDE, "replaced"), v.state.value.sheetError)
        assertEquals(1, g.inner.upserts.size)
    }

    @Test fun completionIsMatchedToTheSubmittedDraftNotTheOneOnScreen() {
        val sub = SubmittedDraft(rect, "Home", "300", null)
        val newer = MapZonesUiState(shape = DrawnShape.Rect(1.0, 1.0, 1.2, 1.2), name = "Other", bufferText = "300", sheet = SheetMode.PAUSE, saving = true, submitted = sub, savedTick = 4)
        val ok = newer.afterSave(sub, "Home", null)
        assertEquals(newer.shape, ok.shape); assertEquals("Other", ok.name); assertEquals(SheetMode.PAUSE, ok.sheet) // a success never discards a newer draft
        assertEquals(5, ok.savedTick); assertFalse(ok.saving); assertTrue(ok.message!!.contains("saved"))
        val refused = newer.afterSave(sub, "Home", "Not saved: inside.")
        assertNull(refused.sheetError); assertTrue(refused.messageIsError); assertTrue(refused.message!!.contains("Not saved: inside.")); assertEquals(newer.shape, refused.shape) // a refusal is not lost
        val same = newer.copy(shape = rect, name = "Home")
        assertEquals("Not saved: inside.", same.afterSave(sub, "Home", "Not saved: inside.").sheetError)
        assertNull(same.afterSave(sub, "Home", null).shape) // the submitted draft is finished
    }

    @Test fun savingIsRefusedLiveWhenSavedZonesBecomeUnreadable() {
        val inner = FakeZoneEditor()
        val v = MapZonesViewModel(inner, newId = { "zone-1" }, work = main.dispatcher).also { vms += it }; v.toPauseSheet()
        inner.snap.value = inner.snap.value.copy(protectedMode = true) // became unreadable AFTER the sheet opened
        v.savePause()
        assertTrue(inner.upserts.isEmpty()); assertEquals(ZoneWords.PROTECTED_BANNER, v.state.value.sheetError); assertFalse(v.state.value.saving)
        inner.snap.value = inner.snap.value.copy(protectedMode = false)
        v.savePause(); assertEquals(1, inner.upserts.size)
    }

    @Test fun pauseSaveButtonFollowsTheLiveProtectedState() {
        assertTrue(PauseSave.enabled(saving = false, unreadable = false))
        assertFalse(PauseSave.enabled(saving = false, unreadable = true)); assertFalse(PauseSave.enabled(saving = true, unreadable = false))
    }
}
