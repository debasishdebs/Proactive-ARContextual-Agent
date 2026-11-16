package com.rokid.context.phone.ui.settings.privacy.zones

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluation
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZonePresets
import com.rokid.context.core.model.zone.ZoneReason
import com.rokid.context.core.model.zone.ZoneState
import com.rokid.context.core.model.zone.ZoneStatus
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.zone.ZoneChange
import com.rokid.context.phone.zone.ZoneSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FakeZoneEditor : ZoneEditor {
    val snap = MutableStateFlow(ZoneSnapshot(emptyList(), ZoneEvaluation(emptyList())))
    override val snapshot: StateFlow<ZoneSnapshot> = snap
    var fix: ZoneFix? = null
    var stored: List<PauseZone> = emptyList()
    override fun storedZones() = stored
    var next: ZoneChange = ZoneChange.OK
    val upserts = mutableListOf<PauseZone>()
    val calls = mutableListOf<String>()
    override fun lastFix() = fix
    override suspend fun upsert(zone: PauseZone): ZoneChange { upserts += zone; if (next == ZoneChange.OK) snap.value = snap.value.copy(zones = snap.value.zones + zone); return next }
    override suspend fun setEnabled(id: String, enabled: Boolean): ZoneChange { calls += "enable:$id:$enabled"; return next }
    override suspend fun remove(id: String): ZoneChange { calls += "remove:$id"; return next }
    override suspend fun addPreset(preset: PauseZone): ZoneChange { calls += "preset:${preset.id}"; if (next == ZoneChange.OK) snap.value = snap.value.copy(zones = snap.value.zones + preset); return next }
}

fun status(id: String, state: ZoneState, reason: ZoneReason = ZoneReason.NONE, dist: Double? = null) = ZoneStatus(id, state, state, reason, dist, 300)

class ZonesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val vms = mutableListOf<ZonesViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private val ed = FakeZoneEditor()
    private fun vm(elapsed: Long = 1_000L) = ZonesViewModel(ed, elapsed = { elapsed }, newId = { "zone-1" }, work = main.dispatcher).also { vms += it }
    private fun kotlinx.coroutines.test.TestScope.watch(v: ZonesViewModel) { backgroundScope.launch(main.dispatcher) { v.state.collect {} } }
    private fun draft(v: ZonesViewModel, name: String = "Camp", s: String = "12.0", w: String = "92.0", n: String = "12.5", e: String = "92.5", b: String = "300") =
        v.editDraft { it.copy(name = name, south = s, west = w, north = n, east = e, buffer = b) }

    @Test fun validDraftBecomesAZoneAndAsksForLocation() = runTest {
        val v = vm(); watch(v); v.setLocationGranted(false)
        draft(v); v.save()
        val z = ed.upserts.single()
        assertEquals("zone-1", z.id); assertEquals(12.0, z.south, 0.0); assertEquals(300, z.bufferM)
        assertTrue(v.state.value.requestPermission)
        assertTrue(v.state.value.message!!.contains("capture stays paused"))
        assertTrue(v.state.value.draft.isEmpty)
    }

    @Test fun grantedLocationDoesNotPrompt() = runTest {
        val v = vm(); watch(v); v.setLocationGranted(true)
        draft(v); v.save()
        assertFalse(v.state.value.requestPermission)
        assertFalse(v.state.value.message!!.contains("paused"))
    }

    @Test fun badDraftsAreRefusedInWordsAndNeverSent() = runTest {
        val v = vm(); watch(v)
        val cases = listOf(
            { draft(v, name = " ") } to "name",
            { draft(v, s = "x") } to "all four bounds",
            { draft(v, s = "NaN") } to "all four bounds",
            { draft(v, s = "-91") } to "Latitude",
            { draft(v, e = "181") } to "Longitude",
            { draft(v, s = "13", n = "12") } to "South must be less than north",
            { draft(v, w = "93", e = "92") } to "West must be less than east",
            { draft(v, b = "50001") } to "Buffer",
            { draft(v, b = "-1") } to "Buffer",
            { draft(v, b = "2.5") } to "Buffer",
        )
        for ((set, text) in cases) { draft(v); set(); v.save(); assertTrue(text, v.state.value.draftError!!.contains(text)) }
        assertTrue(ed.upserts.isEmpty())
        draft(v, b = "0"); v.save(); assertEquals(0, ed.upserts.single().bufferM)
    }

    @Test fun dragFillsTheBoundsInAnyCornerOrderAndTinyDragIsRefused() = runTest {
        val v = vm(); watch(v); v.setDrawing(true)
        v.drawn(LatLon(12.5, 92.5), LatLon(12.0, 92.0))
        val d = v.state.value.draft
        assertEquals("12.00000", d.south); assertEquals("12.50000", d.north); assertEquals("92.00000", d.west); assertEquals("92.50000", d.east)
        assertFalse(v.state.value.drawing)
        v.clearDraft(); v.setDrawing(true)
        v.drawn(LatLon(12.0, 92.0), LatLon(12.0001, 92.0001))
        assertTrue(v.state.value.draft.isEmpty); assertTrue(v.state.value.messageIsError)
    }

    @Test fun blockedInsideIsExplainedAndNothingChanges() = runTest {
        ed.snap.value = ZoneSnapshot(listOf(ZonePresets.JARAWA), ZoneEvaluation(listOf(status("jarawa", ZoneState.INSIDE))))
        val v = vm(); watch(v)
        ed.next = ZoneChange.BLOCKED_INSIDE
        v.setEnabled("jarawa", false)
        val m = v.state.value.message!!
        assertTrue(v.state.value.messageIsError)
        assertTrue(m.contains("inside this zone") && m.contains("turned off") && m.contains("Leave the area"))
        v.askDelete("jarawa"); v.confirmDelete()
        assertTrue(v.state.value.message!!.contains("deleted"))
        assertEquals(listOf("enable:jarawa:false", "remove:jarawa"), ed.calls)
        assertNull(v.state.value.pendingDeleteId)
    }

    @Test fun deleteNeedsAConfirm() = runTest {
        val v = vm(); watch(v)
        v.askDelete("a"); assertEquals("a", v.state.value.pendingDeleteId)
        v.cancelDelete(); assertTrue(ed.calls.isEmpty())
    }

    @Test fun everyEngineRefusalHasText() {
        for (c in ZoneChange.values()) if (c != ZoneChange.OK) assertNotNull(c.name, ZoneWords.refusal(c, "x"))
        assertNull(ZoneWords.refusal(ZoneChange.OK, "x"))
    }

    @Test fun chipsSayWhatAndWhy() {
        val z = ZonePresets.JARAWA
        assertEquals(ChipLevel.OFF, ZoneWords.chip(z.copy(enabled = false), null).level)
        assertEquals("Outside, 12.3 km from the box", ZoneWords.chip(z, status("jarawa", ZoneState.OUTSIDE, dist = 12_300.0)).text)
        assertTrue(ZoneWords.chip(z, status("jarawa", ZoneState.APPROACH, dist = 800.0)).text.startsWith("Approaching, 800 m from the box"))
        assertEquals("Inside: capture is paused", ZoneWords.chip(z, status("jarawa", ZoneState.INSIDE)).text)
        assertTrue(ZoneWords.chip(z, status("jarawa", ZoneState.INSIDE, ZoneReason.NO_FIX)).text.contains("no location fix yet"))
        assertTrue(ZoneWords.chip(z, status("jarawa", ZoneState.INSIDE, ZoneReason.STALE_FIX)).text.contains("too old"))
        assertTrue(ZoneWords.chip(z, status("jarawa", ZoneState.INSIDE, ZoneReason.LOCATION_OFF)).text.contains("off or denied"))
        assertEquals(ChipLevel.PAUSED, ZoneWords.chip(z, null).level)
    }

    @Test fun deniedBannerOnlyWithAnEnabledZone() = runTest {
        val v = vm(); watch(v); v.setLocationGranted(false)
        assertFalse(v.state.value.deniedBanner)
        ed.snap.value = ZoneSnapshot(listOf(ZonePresets.JARAWA), ZoneEvaluation(listOf(status("jarawa", ZoneState.INSIDE, ZoneReason.LOCATION_OFF))))
        assertTrue(v.state.value.deniedBanner)
        v.setLocationGranted(true)
        assertFalse(v.state.value.deniedBanner)
    }

    @Test fun youAppearOnlyWithAFreshFix() = runTest {
        ed.fix = ZoneFix(900L, 12.1, 92.7, 20f)
        val v = vm(elapsed = 1_000L); watch(v)
        ed.snap.value = ed.snap.value.copy(zones = listOf(ZonePresets.JARAWA))
        assertEquals(12.1, v.state.value.you!!.lat, 0.0)
        val old = vm(elapsed = 900L + 4 * 60_000L); watch(old)
        ed.snap.value = ed.snap.value.copy(zones = emptyList())
        assertNull(old.state.value.you)
    }

    @Test fun savedZonesShowWhileTheHubIsStopped() = runTest {
        ed.stored = listOf(ZonePresets.JARAWA, ZonePresets.JARAWA.copy(id = "off", name = "Off one", enabled = false))
        ed.snap.value = ZoneSnapshot(emptyList(), ZoneEvaluation(emptyList()), monitoring = false)
        val v = vm(); watch(v)
        val rows = v.state.value.rows
        assertEquals(listOf("jarawa", "off"), rows.map { it.zone.id })
        assertEquals("Not monitored (hub stopped)", rows[0].chip.text)
        assertFalse(rows[0].inside)
        assertTrue(rows[1].chip.text.startsWith("Off"))
        assertFalse(v.state.value.presetAvailable)
        ed.snap.value = ZoneSnapshot(listOf(ZonePresets.JARAWA), ZoneEvaluation(listOf(status("jarawa", ZoneState.OUTSIDE))))
        assertEquals(listOf("jarawa"), v.state.value.rows.map { it.zone.id })
        assertTrue(v.state.value.rows[0].chip.text.startsWith("Outside"))
    }

    @Test fun presetButtonDisappearsOnceAdded() = runTest {
        val v = vm(); watch(v)
        assertTrue(v.state.value.presetAvailable)
        v.addPreset()
        assertFalse(v.state.value.presetAvailable)
        assertTrue(v.state.value.message!!.contains("UNVERIFIED"))
        assertEquals(listOf("preset:jarawa"), ed.calls)
    }
}
