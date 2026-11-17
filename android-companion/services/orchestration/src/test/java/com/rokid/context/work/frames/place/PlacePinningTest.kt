package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
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
        fix = { fixCalls++; fix }, zoneBlocked = { zone }, nearby = { _, _ -> sites }, load = { stored }, save = { if (saveOk) { stored = it; true } else false },
        elapsed = { now }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) }, log = { logs += it },
    )
    private val form = PlacePinning.KINDS.let { PinForm("  Mirror   Wall ", PlaceKinds.ARCHAEOLOGICAL_SITE, 60, "wd:Q1", "Polished wall,\nold graffiti.") }

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
        val p = PlacePinning(fix = { zone = true; fix }, zoneBlocked = { calls++; zone }, nearby = { _, _ -> sites }, load = { stored }, save = { stored = it; true }, elapsed = { now })
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
}
