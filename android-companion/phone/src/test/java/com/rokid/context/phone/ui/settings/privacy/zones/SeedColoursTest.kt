package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.place.SeedWipeListener
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The seeded-area palette and the saved colour choice: distinct from the pause red and the pin purple, cycles by order, persists keyed by a hash, removed with the area and by Delete all. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedColoursTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val id = "seed:27.000,88.000,27.100,88.100"

    @Test fun paletteIsNineDistinctColoursThatAvoidThePauseRedAndThePinPurple() {
        val c = SeedPalette.colours
        assertTrue(c.size in 8..10); assertEquals(c.size, c.map { it.argb }.toSet().size); assertEquals(c.size, c.map { it.name }.toSet().size)
        val red = 0xFFD32F2F.toInt(); val purple = 0xFF7B3FC4.toInt()
        for (x in c) { assertTrue(dist(x.argb, red) > 70); assertTrue(dist(x.argb, purple) > 70) }
    }
    private fun dist(a: Int, b: Int): Double = Math.sqrt(listOf(16, 8, 0).sumOf { s -> val d = ((a shr s) and 255) - ((b shr s) and 255); (d * d).toDouble() })

    @Test fun defaultsCycleByOrderAndASavedChoiceWins() {
        assertEquals(0, SeedPalette.default(0)); assertEquals(1, SeedPalette.default(1)); assertEquals(0, SeedPalette.default(SeedPalette.colours.size))
        assertEquals(4, SeedPalette.resolve(4, 0)); assertEquals(2, SeedPalette.resolve(null, 2)); assertEquals(2, SeedPalette.resolve(77, 2))
        val rows = SeedOverlays.of((0 until 3).map { SeedAreaInfo("a$it", 1.0, 1.0, 1.1, 1.1, "done") })
        assertEquals(listOf(0, 1, 2), rows.map { it.colour })
        assertEquals("blue", SeedPalette.name(rows[0].colour))
    }

    @Test fun choicePersistsIsKeyedByAHashAndRemovalClearsIt() {
        val s = SeedColourStore.of(app); s.clearAll()
        assertNull(s.get(id)); s.set(id, 3); s.set("other", 6)
        assertEquals(3, SeedColourStore.of(app).get(id)) // a new instance reads the file
        val raw = app.getSharedPreferences(SeedColourStore.PREFS, 0).all
        assertTrue(raw.keys.none { it.contains(id) || it.contains("27.") }); assertEquals(2, raw.size)
        s.set(id, 99); assertEquals(3, s.get(id)) // an invalid index is not stored
        s.clear(id); assertNull(s.get(id)); assertEquals(6, s.get("other"))
    }

    @Test fun deleteAllClearsEveryChoice() = runTest {
        val s = SeedColourStore.of(app); s.set(id, 2); s.set("other", 4)
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        SeedWipeListener(app).onDeleteAll()
        assertNull(s.get(id)); assertNull(s.get("other")); assertFalse(app.getSharedPreferences(SeedColourStore.PREFS, 0).all.isNotEmpty())
    }
}
