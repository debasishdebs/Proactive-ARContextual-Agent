package com.rokid.context.work.frames.place

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val LAT = 12.9716
private const val LON = 77.5946

/** Developer "Add place manually": the form's rules, the real pin path, the source, and the retrieval check (a manual pin is a candidate exactly like a normal one). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ManualPlaceTest {
    private lateinit var db: PlacesDatabase
    private var stored = listOf<UserPlace>()
    private var zones: List<PauseZone>? = emptyList()
    private var ids = 0
    private val logs = mutableListOf<String>()

    @Before fun open() { db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() = db.close()

    private fun pinning() = PlacePinning(
        fix = { PlaceFix(LAT, LON, 1_000, 10f) }, zoneBlocked = { false }, zones = { zones }, nearby = { _, _ -> emptyList() }, load = { stored }, save = { stored = it; true },
        elapsed = { 1_000L }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) }, log = { logs += it },
    )

    @Test fun coordinateFormats() {
        val ok = listOf("12.9716, 77.5946", "12.9716 77.5946", "12.9716;77.5946", "(12.9716, 77.5946)", " 12.9716,77.5946 ", "12.9716° N, 77.5946° E", "12.9716N 77.5946E")
        for (t in ok) assertEquals(t, 12.9716 to 77.5946, ManualPlace.parseCoordinates(t))
        assertEquals(-33.8688 to 151.2093, ManualPlace.parseCoordinates("-33.8688, 151.2093"))
        assertEquals(-33.8688 to -70.5, ManualPlace.parseCoordinates("33.8688 S, 70.5 W"))
        for (t in listOf("", "12.9", "12.9, 77.5, 3", "abc, def", "91, 10", "-91, 10", "10, 181", "10, -181", "NaN, 1", "1e3, 5", "12.9 E, 77.5 N", "-12.9S, 5", "12,9, 77,5")) assertNull(t, ManualPlace.parseCoordinates(t))
    }

    @Test fun radiusRangeAndDefaultByKind() {
        assertEquals(60, ManualPlace.parseRadius("", PlaceKinds.ATTRACTION)); assertEquals(250, ManualPlace.parseRadius("  ", PlaceKinds.FORT))
        assertEquals(10, ManualPlace.parseRadius("10", PlaceKinds.FORT)); assertEquals(1500, ManualPlace.parseRadius("1500", PlaceKinds.FORT))
        for (t in listOf("9", "1501", "-5", "0", "abc", "12.5")) assertNull(t, ManualPlace.parseRadius(t, PlaceKinds.FORT))
        for (k in ManualPlace.KINDS) assertTrue(ManualPlace.defaultRadius(k) in UserPlace.MIN_RADIUS_M..PlaceKinds.MAX_RADIUS_M)
    }

    @Test fun validationFaults() {
        val k = PlaceKinds.ATTRACTION; val c = "12.9716, 77.5946"
        assertNull(ManualPlace.fault("Office", "", k, c, ""))
        assertEquals(ManualFault.NAME, ManualPlace.fault("   ", "", k, c, ""))
        assertEquals(ManualFault.NAME, ManualPlace.fault("x".repeat(81), "", k, c, ""))
        assertEquals(ManualFault.COORDINATES, ManualPlace.fault("Office", "", k, "95, 10", ""))
        assertEquals(ManualFault.RADIUS, ManualPlace.fault("Office", "", k, c, "5"))
        assertEquals(ManualFault.NOTE, ManualPlace.fault("Office", "n".repeat(141), k, c, ""))
        assertEquals(ManualFault.KIND, ManualPlace.fault("Office", "", "person", c, ""))
    }

    @Test fun createsARealPinThroughThePinPath() = runBlocking {
        val p = pinning()
        val r = ManualPlace.create(p, "  My   Office ", "Ground floor\nreception", PlaceKinds.ATTRACTION, "12.9716, 77.5946", "") as ManualResult.Saved
        assertEquals(listOf(r.place), stored); assertEquals("My Office", r.place.name); assertEquals("Ground floor reception", r.place.note)
        assertEquals(60, r.place.radiusM); assertEquals(12.9716, r.place.lat, 0.0); assertNull(r.place.momentId); assertNull(r.place.parentId); assertNotNull(r.place.pinnedAt); assertTrue(r.place.isValid)
        assertEquals("manual", r.place.toEntity().source); assertTrue(r.place.toEntity().pinned)
        assertTrue(logs.none { "Office" in it || "12.97" in it }) // user data never reaches the log
        // a second place; the same name, kind and spot is one place
        assertEquals(ManualFault.DUPLICATE, (ManualPlace.create(p, "my office", "", PlaceKinds.ATTRACTION, "12.97161, 77.59461", "") as ManualResult.Invalid).fault)
        assertTrue(ManualPlace.create(p, "My Office", "", PlaceKinds.ATTRACTION, "12.99, 77.60", "") is ManualResult.Saved); assertEquals(2, stored.size)
    }

    @Test fun invalidFormStoresNothing() = runBlocking {
        val p = pinning()
        assertEquals(ManualFault.COORDINATES, (ManualPlace.create(p, "A", "", PlaceKinds.VIEWPOINT, "nope", "") as ManualResult.Invalid).fault)
        assertEquals(ManualFault.NAME, (ManualPlace.create(p, "", "", PlaceKinds.VIEWPOINT, "1, 2", "") as ManualResult.Invalid).fault)
        assertEquals(ManualFault.RADIUS, (ManualPlace.create(p, "A", "", PlaceKinds.VIEWPOINT, "1, 2", "2000") as ManualResult.Invalid).fault)
        assertTrue(stored.isEmpty())
    }

    @Test fun insideAPauseZoneIsRefusedAndStoresNothing() = runBlocking {
        zones = listOf(PauseZone("z1", "Z", LAT - 0.0005, LON - 0.0005, LAT + 0.0005, LON + 0.0005, 100))
        val r = ManualPlace.create(pinning(), "Office", "", PlaceKinds.ATTRACTION, "$LAT, $LON", "") as ManualResult.Refused
        assertEquals(PinRefusal.ZONE, r.why); assertTrue(stored.isEmpty())
        zones = null // unreadable zones refuse too
        assertEquals(PinRefusal.ZONE, (ManualPlace.create(pinning(), "Office", "", PlaceKinds.ATTRACTION, "$LAT, $LON", "") as ManualResult.Refused).why)
    }

    @Test fun theListCapRefuses() = runBlocking {
        stored = (1..com.rokid.context.memory.place.UserPinStore.MAX_PINS).map { UserPlace("user:00000000-0000-0000-0000-%012d".format(1000 + it), "P$it", PlaceKinds.VIEWPOINT, 1.0 + it / 1000.0, 2.0) }
        assertEquals(PinRefusal.INVALID, (ManualPlace.create(pinning(), "Extra", "", PlaceKinds.VIEWPOINT, "50, 50", "") as ManualResult.Refused).why)
    }

    @Test fun manualAndLegacyRowsAreBothUserPlacesAndTheCardLabelsAreTheSame() {
        val e = UserPlace("user:00000000-0000-0000-0000-000000000001", "Office", PlaceKinds.ATTRACTION, LAT, LON, 60, null, "Ground floor.").toEntity()
        assertEquals("manual", e.source); assertEquals(PlaceCards.USER_LABEL, PlaceCards.sourceLabel(e)); assertEquals("Ground floor.", PlaceCards.introOf(e, 100))
        val legacy = e.copy(source = "user")
        assertEquals(PlaceCards.USER_LABEL, PlaceCards.sourceLabel(legacy)); assertEquals("Ground floor.", PlaceCards.introOf(legacy, 100))
        assertTrue(UserPlace.isUserSource("manual") && UserPlace.isUserSource("user") && !UserPlace.isUserSource("osm"))
    }

    @Test fun aManualPinIsMirroredToPlacesDbAndDeletedLikeAnyPin() = runBlocking {
        val pack = PlacePack(db, null, PlacePack.DEFAULT_BUDGET_BYTES, { 100 * PlacePack.DAY_MS }, { })
        val p = pinning()
        val a = (ManualPlace.create(p, "Office", "", PlaceKinds.ATTRACTION, "$LAT, $LON", "") as ManualResult.Saved).place
        pack.replaceUserPlaces(stored)
        assertEquals(listOf("manual"), db.dao().userPlaces().map { it.source })
        assertTrue(p.delete(a.id)); pack.replaceUserPlaces(stored)
        assertTrue(db.dao().userPlaces().isEmpty()); assertTrue(stored.isEmpty())
    }

    // ---- retrieval: the real pack lookup and the real sampler, offline ----

    private fun cardFor(pin: UserPlace): PlaceCard = runBlocking {
        db.clearAllTables()
        val pack = PlacePack(db, null, PlacePack.DEFAULT_BUDGET_BYTES, { 100 * PlacePack.DAY_MS }, { })
        pack.replaceUserPlaces(listOf(pin))
        var card: PlaceCard? = null; val dbg = mutableListOf<String>()
        val fixAt = PlaceFix(LAT + 80.0 / 111_320.0, LON, 1_000, 10f) // 80 m north of the pin, a fake GPS fix
        val store = object : PlaceFrameStore {
            override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", 9_995_000L)
            override suspend fun tombstoned(frameId: String) = false
            override suspend fun movedSince(sinceReceivedMs: Long) = false
        }
        kotlinx.coroutines.test.runTest {
            val s = PlaceSampler(
                scope = this, store = store, fix = { fixAt },
                candidates = { la, lo, _ -> pack.candidates(la, lo, networkUp = false) },
                match = { _, _ -> error("offline: the model is never called") },
                saveMemory = { true }, onCard = { card = it }, clock = { 10_000_000L }, enabled = { true }, zoneBlocked = { false }, networkUp = { false }, zones = { emptyList() },
                admit = { true }, photoAllowed = { true }, generation = { 0L }, flight = PlaceFlight(), log = { dbg += it }, stats = { dbg += "out=$it" },
            )
            s.poke(); runCurrent()
            var spins = 0
            while (card == null && spins++ < 100) { Thread.sleep(50); runCurrent() } // the pack answers on Room's own thread
        }
        card ?: error(dbg.joinToString("|"))
    }

    @Test fun aManualPinNearAFakeFixIsACandidateAndShowsItsDescriptionOfflineExactlyLikeANormalPin() = runBlocking {
        val manual = (ManualPlace.create(pinning(), "My Office", "Ground floor, ask for Ravi.", PlaceKinds.ATTRACTION, "$LAT, $LON", "100") as ManualResult.Saved).place
        stored = emptyList()
        val p = pinning(); p.begin().getOrThrow()
        val normal = (p.create(PinForm("My Office", PlaceKinds.ATTRACTION, 100, null, "Ground floor, ask for Ravi.")) as PinResult.Saved).place
        val a = cardFor(manual); val b = cardFor(normal)
        assertEquals("My Office", a.title); assertTrue(a.line, a.line.startsWith("Ground floor, ask for Ravi. · "))
        assertEquals(a.copy(placeId = ""), b.copy(placeId = "")) // same card, same text, same source label as a normally created pin
        assertEquals(manual.id, a.placeId)
    }
}
