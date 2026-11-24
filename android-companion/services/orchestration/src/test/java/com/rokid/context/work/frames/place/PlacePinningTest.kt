package com.rokid.context.work.frames.place

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LAT = 7.957
private const val LON = 80.759

class PlacePinningTest {
    private var now = 1_000_000L
    private var zone = false
    private var zoneList: List<PauseZone>? = emptyList()
    private var timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var cancelled = 0
    private var fix: PlaceFix? = PlaceFix(LAT, LON, 2_000, 12f)
    private var stored = listOf<UserPlace>()
    private var saveOk = true
    private var fixCalls = 0
    private val logs = mutableListOf<String>()
    private var ids = 0
    private val sites = listOf(
        PlaceEntity("wd:Q1", null, "Sigiriya", PlaceKinds.ARCHAEOLOGICAL_SITE, LAT, LON, 250, "s", "wikipedia:en", "x", 1, false, false, 0, 10),
        PlaceEntity("user:00000000-0000-0000-0000-000000000001", null, "Old pin", PlaceKinds.VIEWPOINT, LAT, LON, 60, "n", "user", "user", 0, false, true, 0, 10),
    )

    private fun pinning() = PlacePinning(
        fix = { fixCalls++; fix }, zoneBlocked = { zone }, zones = { zoneList }, schedule = { d, r -> timers += d to r; { cancelled++ } }, nearby = { _, _ -> sites }, load = { stored }, save = { if (saveOk) { stored = it; true } else false },
        elapsed = { now }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) }, log = { logs += it }, onCreated = { created += it },
    )
    private val created = mutableListOf<String>()
    private val form = PlacePinning.KINDS.let { PinForm("  Mirror   Wall ", PlaceKinds.ARCHAEOLOGICAL_SITE, 60, "wd:Q1", "Polished wall,\nold graffiti.") }

    @Test fun create_emitsPinCreatedOnlyOnSave() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        saveOk = false; assertTrue(p.create(form) is PinResult.Refused); assertTrue(created.isEmpty())
        saveOk = true; p.begin().getOrThrow(); val r = p.create(form) as PinResult.Saved
        assertEquals(listOf(r.place.id), created)
    }

    @Test fun aFreshFixOutsideEveryZoneMakesAPinWithCleanWordsAndTheNearbySitesAreOffered() = runBlocking {
        val p = pinning()
        val parents = p.begin().getOrThrow()
        assertEquals(listOf("wd:Q1"), parents.map { it.id }) // a user pin is never offered as a parent
        val r = p.create(form) as PinResult.Saved
        assertEquals("Mirror Wall", r.place.name); assertEquals("Polished wall, old graffiti.", r.place.note); assertEquals("wd:Q1", r.place.parentId)
        assertEquals(LAT, r.place.lat, 0.0); assertEquals(60, r.place.radiusM); assertTrue(r.place.isValid)
        assertEquals(listOf(r.place), stored)
    }

    @Test fun defaultRadiusIs60Metres() { assertEquals(60, UserPlace.DEFAULT_RADIUS_M); assertEquals(60, PinForm("x", PlaceKinds.VIEWPOINT).radiusM) }

    @Test fun insideOrNearAZoneOrWithAnUnknownZoneStateNothingIsPinned() = runBlocking {
        zone = true
        val p = pinning()
        val e = p.begin().exceptionOrNull() as PlacePinning.PinRefused
        assertEquals(PinRefusal.ZONE, e.why); assertEquals("the fix is not even asked for while a zone blocks", 0, fixCalls)
        assertTrue(stored.isEmpty()); assertEquals(PinRefusal.DRAFT_EXPIRED, (p.create(form) as PinResult.Refused).why)
    }

    @Test fun aZoneThatClosesBetweenTheFixAndTheSaveRefusesTheSave() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        zone = true
        assertEquals(PinRefusal.ZONE, (p.create(form) as PinResult.Refused).why)
        assertTrue(stored.isEmpty())
        zone = false
        assertEquals("the draft is gone: the position is used once", PinRefusal.DRAFT_EXPIRED, (p.create(form) as PinResult.Refused).why)
    }

    @Test fun aZoneThatClosesWhileTheFixIsBeingTakenRefusesAtOnce() = runBlocking {
        var calls = 0
        val p = PlacePinning(fix = { zone = true; fix }, zoneBlocked = { calls++; zone }, zones = { emptyList() }, nearby = { _, _ -> sites }, load = { stored }, save = { stored = it; true }, elapsed = { now })
        assertEquals(PinRefusal.ZONE, (p.begin().exceptionOrNull() as PlacePinning.PinRefused).why)
        assertEquals(2, calls)
    }

    @Test fun onlyAFreshAccurateFixIsUsed() = runBlocking {
        for (bad in listOf(null, PlaceFix(LAT, LON, 61_000, 10f), PlaceFix(LAT, LON, -1, 10f), PlaceFix(LAT, LON, 1_000, 51f), PlaceFix(LAT, LON, 1_000, Float.NaN), PlaceFix(Double.NaN, LON, 1_000, 5f))) {
            fix = bad
            assertEquals("$bad", PinRefusal.NO_FRESH_FIX, (pinning().begin().exceptionOrNull() as PlacePinning.PinRefused).why)
        }
        fix = PlaceFix(LAT, LON, 60_000, 50f); assertTrue("the limits themselves are fresh", pinning().begin().isSuccess)
    }

    @Test fun theDraftExpiresAndNoPositionIsKeptAfterCancel() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        now += PlacePinning.DRAFT_TTL_MS + 2_001 // the fix was 2 s old when taken
        assertEquals(PinRefusal.DRAFT_EXPIRED, (p.create(form) as PinResult.Refused).why)
        p.begin().getOrThrow(); p.cancel()
        assertEquals(PinRefusal.DRAFT_EXPIRED, (p.create(form) as PinResult.Refused).why)
    }

    @Test fun invalidFormsAndAFailedStoreChangeNothing() = runBlocking {
        for (bad in listOf(form.copy(name = " "), form.copy(radiusM = 5), form.copy(radiusM = 5000), form.copy(kind = "person"), form.copy(parentId = "user:00000000-0000-0000-0000-000000000009"))) {
            val p = pinning(); p.begin().getOrThrow()
            assertEquals("$bad", PinRefusal.INVALID, (p.create(bad) as PinResult.Refused).why)
        }
        saveOk = false
        val p = pinning(); p.begin().getOrThrow()
        assertEquals(PinRefusal.NOT_STORED, (p.create(form) as PinResult.Refused).why)
        assertTrue(stored.isEmpty())
    }

    @Test fun aLongNoteIsCutTo140AndIsTheOnlyCardText() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        val r = p.create(form.copy(note = "w ".repeat(200))) as PinResult.Saved
        assertTrue(r.place.note.length <= 140)
    }

    @Test fun editChangesWordsKindRadiusAndNoteButNeverThePosition() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        val a = (p.create(form) as PinResult.Saved).place
        val e = p.edit(a.id, PinForm("Lion's Paw", PlaceKinds.VIEWPOINT, 90, null, "Two huge paws.")) as PinResult.Saved
        assertEquals("Lion's Paw", e.place.name); assertEquals(90, e.place.radiusM); assertNull(e.place.parentId); assertEquals(a.lat, e.place.lat, 0.0); assertEquals(a.lon, e.place.lon, 0.0)
        assertEquals(PinRefusal.INVALID, (p.edit(a.id, PinForm("", PlaceKinds.VIEWPOINT)) as PinResult.Refused).why)
        assertEquals(PinRefusal.INVALID, (p.edit("user:none", PinForm("x", PlaceKinds.VIEWPOINT)) as PinResult.Refused).why)
        assertEquals("edit needs no fix and no zone check", 1, fixCalls)
    }

    @Test fun deleteRemovesOnePinAndTheFullListRefusesMore() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        val a = (p.create(form) as PinResult.Saved).place
        assertFalse(p.delete("user:nope")); assertTrue(p.delete(a.id)); assertTrue(stored.isEmpty())
        stored = List(com.rokid.context.memory.place.UserPinStore.MAX_PINS) { UserPlace("user:00000000-0000-0000-0000-%012d".format(1000 + it), "p$it", PlaceKinds.VIEWPOINT, LAT, LON) }
        p.begin().getOrThrow(); assertEquals(PinRefusal.INVALID, (p.create(form) as PinResult.Refused).why)
    }

    @Test fun logsAreLiteralTextAndCountsNeverANameOrPosition() = runBlocking {
        val p = pinning(); p.begin().getOrThrow(); val a = (p.create(form) as PinResult.Saved).place
        p.edit(a.id, PinForm("Secret Name", PlaceKinds.VIEWPOINT, 70, null, "secret note")); p.delete(a.id)
        zone = true; p.begin()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.all { l -> listOf("Mirror", "Secret", "note", "7.95", "80.75", "user:").none { it in l } })
    }

    @Test fun theUserCardIsTheNoteLabelledYourNoteAndNeverAWikipediaSummary() {
        val mine = UserPlace("user:00000000-0000-0000-0000-000000000001", "Mirror Wall", PlaceKinds.ARCHAEOLOGICAL_SITE, LAT, LON, 60, "wd:Q1", "Polished wall. With graffiti from 8th century visitors.").toEntity()
        val c = PlaceCards.photo(mine, "f1")
        assertEquals("Polished wall. With graffiti from 8th century visitors.", c.line) // the whole note, not the first sentence
        assertEquals("Your note", c.sourceLabel); assertEquals("Mirror Wall", c.title); assertEquals(PlaceConfidence.PHOTO, c.confidence)
        assertEquals("Archaeological site", PlaceCards.photo(mine.copy(summary = ""), "f1").line)
        assertEquals("nearby card for a pin is name and distance only", "nearby · not checked", PlaceCards.nearby(mine, 30.0, "f1").sourceLabel)
        assertEquals("Wikipedia", PlaceCards.sourceLabel(mine.copy(source = "wikipedia:en", fetchedAt = 0)))
    }

    private fun zoneAt(lat: Double, lon: Double, buffer: Int = 300) = PauseZone("z1", "Home", lat - 0.001, lon - 0.001, lat + 0.001, lon + 0.001, buffer)

    @Test fun anOnDemandFixInsideAZoneIsRefusedWhileTheControllerStillSaysOutside() = runBlocking {
        zone = false; zoneList = listOf(zoneAt(LAT, LON))
        val e = pinning().begin().exceptionOrNull() as PlacePinning.PinRefused
        assertEquals(PinRefusal.ZONE, e.why); assertTrue(stored.isEmpty())
    }

    @Test fun aFixInTheApproachBandOrWithAVagueAccuracyNearAZoneIsRefusedAndAFarFixIsFine() = runBlocking {
        zoneList = listOf(zoneAt(LAT + 0.015, LON)) // about 1.5 km north of the fix: inside the 300 m + 2 km band
        assertEquals(PinRefusal.ZONE, (pinning().begin().exceptionOrNull() as PlacePinning.PinRefused).why)
        zoneList = listOf(zoneAt(LAT + 0.05, LON)) // about 5.4 km away
        assertTrue(pinning().begin().isSuccess)
        zoneList = listOf(zoneAt(LAT + 0.05, LON).copy(enabled = false)); assertTrue("a disabled zone does not count", pinning().begin().isSuccess)
        zoneList = listOf(zoneAt(LAT + 0.05, LON).copy(name = " ")); assertEquals("an enabled invalid zone is uncertain", PinRefusal.ZONE, (pinning().begin().exceptionOrNull() as PlacePinning.PinRefused).why)
    }

    @Test fun unreadableZonesRefuseAtDraftAndAtSave() = runBlocking {
        zoneList = null
        assertEquals(PinRefusal.ZONE, (pinning().begin().exceptionOrNull() as PlacePinning.PinRefused).why)
        zoneList = emptyList()
        val p = pinning(); p.begin().getOrThrow()
        zoneList = null
        assertEquals(PinRefusal.ZONE, (p.create(form) as PinResult.Refused).why); assertTrue(stored.isEmpty())
    }

    @Test fun aZoneDrawnOverTheDraftPositionBeforeSaveRefusesTheSaveWithTheFixCoordinates() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        zoneList = listOf(zoneAt(LAT, LON)) // the controller is never asked to agree: only the geometry of the held fix
        assertEquals(PinRefusal.ZONE, (p.create(form) as PinResult.Refused).why); assertTrue(stored.isEmpty())
    }

    @Test fun overlappingDeleteAndEditCannotOverwriteEachOther() = runBlocking {
        val a = UserPlace("user:00000000-0000-0000-0000-00000000000a", "A", PlaceKinds.VIEWPOINT, LAT, LON, 60, null, "")
        val b = a.copy(id = "user:00000000-0000-0000-0000-00000000000b", name = "B")
        stored = listOf(a, b)
        val slow = PlacePinning(fix = { fix }, zoneBlocked = { false }, zones = { emptyList() }, nearby = { _, _ -> sites }, load = { stored }, save = { delay(20); stored = it; true }, elapsed = { now })
        val other = PlacePinning(fix = { fix }, zoneBlocked = { false }, zones = { emptyList() }, nearby = { _, _ -> sites }, load = { stored }, save = { delay(5); stored = it; true }, elapsed = { now })
        val jobs = listOf(async { slow.delete(a.id) }, async { other.edit(b.id, PinForm("B2", PlaceKinds.VIEWPOINT, 70)) }, async { slow.edit(b.id, PinForm("B3", PlaceKinds.VIEWPOINT, 80)) })
        jobs.awaitAll()
        assertEquals("the deleted pin stays deleted and the edits did not resurrect it", listOf(b.id), stored.map { it.id })
        assertTrue(stored.single().name in setOf("B2", "B3"))
    }

    @Test fun theDraftIsClearedWhenThreeMinutesPassWithoutASaveAndTheTimerIsCancelledWithIt() = runBlocking {
        val p = pinning(); p.begin().getOrThrow()
        assertTrue(p.hasDraft); assertEquals(PlacePinning.DRAFT_TTL_MS, timers.single().first)
        timers.single().second() // the fake clock reaches three minutes
        assertFalse("no coordinates are held any more", p.hasDraft)
        assertEquals(PinRefusal.DRAFT_EXPIRED, (p.create(form) as PinResult.Refused).why)
        // a stale timer never clears a newer draft
        val q = pinning(); q.begin().getOrThrow(); val old = timers.last().second; q.begin().getOrThrow(); old(); assertTrue(q.hasDraft)
        val before = cancelled; q.expire(); assertFalse(q.hasDraft); assertTrue("the timer is cancelled with the draft", cancelled > before)
        val r = pinning(); r.begin().getOrThrow(); val c0 = cancelled; r.cancel(); assertFalse(r.hasDraft); assertTrue(cancelled > c0)
        val s = pinning(); s.begin().getOrThrow(); val c1 = cancelled; s.create(form); assertTrue("a save cancels it too", cancelled > c1)
    }
}
