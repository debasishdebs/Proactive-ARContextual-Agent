package com.rokid.context.memory.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UserPlaceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE)
    private fun pin(name: String = "Mirror Wall", parent: String? = "wd:Q1", note: String = "Polished plaster wall, with old graffiti.") =
        UserPlace(UserPlace.newId(), name, PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 60, parent, note)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun aPinIsValidOnlyWithinTheLimits() {
        assertTrue(pin().isValid)
        assertEquals("user:", UserPlace.PREFIX); assertTrue(pin().id.startsWith("user:"))
        assertFalse(pin().copy(id = "wd:Q1").isValid)
        assertFalse(pin().copy(id = "user:not-a-uuid").isValid)
        assertFalse(pin().copy(name = "  ").isValid)
        assertFalse(pin().copy(name = "x".repeat(81)).isValid)
        assertFalse(pin().copy(kind = "person").isValid)
        assertFalse(pin().copy(radiusM = 9).isValid); assertFalse(pin().copy(radiusM = PlaceKinds.MAX_RADIUS_M + 1).isValid)
        assertTrue(pin().copy(radiusM = 10).isValid); assertTrue(pin().copy(radiusM = PlaceKinds.MAX_RADIUS_M).isValid)
        assertFalse(pin().copy(note = "n".repeat(141)).isValid); assertTrue(pin().copy(note = "n".repeat(140)).isValid)
        assertFalse(pin().copy(note = "two\nlines").isValid)
        assertFalse(pin().copy(lat = 91.0).isValid); assertFalse(pin().copy(lon = Double.NaN).isValid)
        assertFalse("a pin is never the child of a pin", pin().copy(parentId = "user:00000000-0000-0000-0000-000000000000").isValid)
        val p = pin(); assertFalse("never its own parent", p.copy(parentId = p.id).isValid)
        assertEquals(UserPlace.DEFAULT_RADIUS_M, 60)
    }

    @Test fun cleaningMakesOneLineAndCuts() {
        assertEquals("a b c", UserPlace.cleanNote("  a\n b\t c "))
        assertEquals(140, UserPlace.cleanNote("x".repeat(500)).length)
        assertEquals(80, UserPlace.cleanName("y".repeat(200)).length)
    }

    @Test fun theRowIsPinnedUserSourcedAndCarriesTheNoteAsItsSummary() {
        val r = pin().toEntity(lastUsedDay = 7)
        assertTrue(r.pinned); assertFalse(r.seeded); assertEquals("user", r.source); assertEquals("Polished plaster wall, with old graffiti.", r.summary)
        assertEquals(7, r.lastUsedDay); assertTrue(r.bytes > 0); assertEquals(0L, r.fetchedAt)
    }

    @Test fun theStoreRoundTripsAndRefusesInvalidOrRepeatedPins() {
        val s = UserPinStore(prefs)
        assertEquals(emptyList<UserPlace>(), s.pins())
        val a = pin(); val b = pin("Lion's Paw", parent = null, note = "")
        assertTrue(s.write(listOf(a, b)))
        assertEquals(listOf(a, b), UserPinStore(prefs).pins())
        assertFalse(s.write(listOf(a, a))); assertFalse(s.write(listOf(a.copy(name = ""))))
        assertEquals("a refused write changes nothing", listOf(a, b), s.pins())
        assertFalse(s.write(List(UserPinStore.MAX_PINS + 1) { pin("p$it") }))
        assertNull(UserPinStore.parse(UserPinStore.encode(listOf(b))).single().parentId)
    }

    @Test fun anUnreadableListIsCorruptAndIsNeverOverwritten() {
        prefs.edit().putString("pins", "{not json").commit()
        val s = UserPinStore(prefs)
        assertTrue(s.corrupt); assertEquals(emptyList<UserPlace>(), s.pins())
        assertFalse("never overwrites what cannot be read", s.write(listOf(pin())))
        assertEquals("{not json", prefs.getString("pins", null))
        prefs.edit().putString("pins", UserPinStore.encode(listOf(pin().copy(name = "")))).commit() // valid JSON holding an invalid pin
        assertTrue(UserPinStore(prefs).corrupt)
    }

    @Test fun thePrefsFileIsNamedPlacePins() { assertEquals("place_pins", UserPinStore.PREFS) }

    // ---- slice 1.4.2: the optional reference Moment link (pin storage is a prefs JSON list, not Room: the "migration" is a new optional key) ----

    @Test fun pinsStoredBeforeTheMomentLinkStillReadAndCarryNoLink() {
        // exactly the JSON an older build wrote: no "moment" key
        val id = "user:00000000-0000-0000-0000-0000000000aa"
        prefs.edit().putString("pins", """[{"id":"$id","name":"Old","kind":"${PlaceKinds.MONUMENT}","lat":7.9,"lon":80.7,"r":60,"parent":null,"note":"n"}]""").commit()
        val store = UserPinStore(prefs)
        val p = store.pins().single()
        assertEquals("Old", p.name); assertNull(p.momentId); assertFalse(store.corrupt)
        assertTrue(store.write(listOf(p.copy(name = "Renamed")))) // writing back keeps working and adds no key
        assertFalse("moment" in prefs.getString("pins", "")!!)
    }

    @Test fun theMomentLinkRoundTripsAndAnUnreadableOneIsNoLinkNotACorruptList() {
        val store = UserPinStore(prefs); val p = pin().copy(momentId = "0000001f")
        assertTrue(store.write(listOf(p))); assertEquals("0000001f", store.pins().single().momentId)
        val id = p.id
        prefs.edit().putString("pins", """[{"id":"$id","name":"Old","kind":"${PlaceKinds.MONUMENT}","lat":7.9,"lon":80.7,"r":60,"parent":null,"note":"","moment":"not hex"}]""").commit()
        assertNull(store.pins().single().momentId); assertFalse(store.corrupt)
        assertFalse(p.copy(momentId = "XYZ").isValid)
    }

    @Test fun setMomentAndClearMomentsOnlyTouchTheLinkAndFireNoPinChangeListener() {
        val store = UserPinStore(prefs); val a = pin("A"); val b = pin("B"); assertTrue(store.write(listOf(a, b)))
        val heard = ArrayList<Set<String>>(); val l: (Set<String>) -> Unit = { heard += it }
        UserPinStore.addChangeListener(l)
        try {
            assertTrue(store.setMoment(a.id, "00000001")); assertTrue(store.setMoment(b.id, "00000002"))
            assertFalse(store.setMoment("user:00000000-0000-0000-0000-00000000ffff", "00000003")); assertFalse(store.setMoment(a.id, "zz"))
            assertTrue("a link alone is no edit of the pin", heard.isEmpty())
            assertEquals(setOf(a.id), store.clearMomentsExcept(setOf("00000002")))
            assertEquals(listOf(null, "00000002"), store.pins().map { it.momentId }); assertEquals(2, store.pins().size) // the pins stay
            assertEquals(setOf(b.id), store.clearAllMoments()); assertTrue(store.pins().all { it.momentId == null })
            assertTrue(store.setMoment(a.id, "00000004")); assertTrue(store.write(listOf(a.copy(momentId = "00000004", name = "A2"), b)))
            assertEquals("a real edit still notifies", listOf(setOf(a.id)), heard)
        } finally { UserPinStore.removeChangeListener(l) }
    }

    @Test fun anUnreadableStoredListIsNeverRewrittenByTheLinkOperations() {
        prefs.edit().putString("pins", "not json").commit()
        val store = UserPinStore(prefs)
        assertFalse(store.setMoment("user:00000000-0000-0000-0000-00000000ffff", "00000001")); assertNull(store.clearMomentsExcept(emptySet())); assertNull(store.clearAllMoments())
        assertEquals("not json", prefs.getString("pins", null))
    }

    @Test fun theExportCopyOfThePinsCarriesNoMomentLinkAndTheStoreKeepsIt() {
        val p = pin().copy(momentId = "0000002a"); val raw = UserPinStore.encode(listOf(p))
        assertTrue("moment" in raw)
        val stripped = UserPinStore.withoutLinks(raw)
        assertFalse("moment" in stripped); assertFalse("0000002a" in stripped)
        assertNull(UserPinStore.parse(stripped).single().momentId); assertEquals(p.name, UserPinStore.parse(stripped).single().name)
        assertEquals("not json", UserPinStore.withoutLinks("not json"))
    }

    @Test fun pinnedAtIsWrittenOnlyWhenSetReadBackAndOldPinsHaveNone() {
        val store = UserPinStore(prefs)
        assertTrue(store.write(listOf(pin("Old"), pin("New").copy(pinnedAt = 1_800_000_000_000L))))
        assertEquals(listOf(null, 1_800_000_000_000L), store.pins().map { it.pinnedAt })
        assertEquals("only the new pin carries the key", 1, Regex("\"at\"").findAll(prefs.getString("pins", "")!!).count())
        assertFalse(pin().copy(pinnedAt = 0L).isValid)
        assertTrue("the settings export may keep it", UserPinStore.withoutLinks(UserPinStore.encode(listOf(pin().copy(pinnedAt = 5L)))).contains("\"at\""))
    }

    @Test fun anArmingIsStoredReadBackMarkedCommittedAndDropped() {
        val store = UserPinStore(prefs); val a = UserPinStore.Arming("op", "forget", false, mapOf("user:x" to "00000001"))
        assertTrue(store.arm(a)); assertEquals(a, store.arming()); assertFalse(store.markCommitted("other"))
        assertTrue(store.markCommitted("op")); assertTrue(store.arming()!!.committed)
        assertTrue(store.disarm()); assertNull(store.arming()); assertFalse(store.hasArmingRecord())
        prefs.edit().putString(UserPinStore.ARMED_KEY, "garbage").commit()
        assertNull("an unreadable record is no arming", store.arming()); assertTrue(store.hasArmingRecord())
    }
}
