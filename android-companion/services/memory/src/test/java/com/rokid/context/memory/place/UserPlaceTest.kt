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
}
