package com.rokid.context.connectors.sources

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EnabledSourcesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SourceSettingsStore

    private class Fake(override val kind: SourceKind, override val permissions: List<String>, val out: List<String>) : ContextSource {
        var calls = 0
        override suspend fun lines(question: String?, nowMs: Long): List<String> { calls++; return out }
    }

    @Before fun setUp() {
        val prefs = ctx.getSharedPreferences("sources_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        settings = SourceSettingsStore(prefs)
    }

    @Test fun disabledSourcesContributeNothing() = runBlocking {
        val fakes = SourceKind.entries.map { Fake(it, emptyList(), listOf("x $it")) }
        val e = EnabledSources(fakes, settings, { true }, Dispatchers.Unconfined)
        assertEquals(emptyList<String>(), e.lines("q", 1L))
        assertTrue(fakes.all { it.calls == 0 })
        assertEquals(6, settings.nudgesPerDay.value)
    }

    @Test fun revokedPermissionYieldsEmptyAndFlag() = runBlocking {
        val cal = Fake(SourceKind.CALENDAR, listOf("android.permission.READ_CALENDAR"), listOf("event \u00b7 a"))
        settings.set(SourceKind.CALENDAR, true)
        val e = EnabledSources(listOf(cal), settings, { false }, Dispatchers.Unconfined)
        assertEquals(emptyList<String>(), e.lines(null, 1L))
        assertEquals(0, cal.calls)
        assertEquals(setOf(SourceKind.CALENDAR), e.needsPermission().value)
    }

    @Test fun throwingSourceYieldsNothing() = runBlocking {
        val bad = object : ContextSource {
            override val kind = SourceKind.NOTES
            override val permissions = emptyList<String>()
            override suspend fun lines(question: String?, nowMs: Long): List<String> = throw SecurityException("revoked")
        }
        settings.set(SourceKind.NOTES, true)
        assertEquals(emptyList<String>(), EnabledSources(listOf(bad), settings, { true }, Dispatchers.Unconfined).lines(null, 1L))
    }

    @Test fun boundedToFifteenHundredChars() = runBlocking {
        val long = (1..40).map { i -> "note \u00b7 $i " + "x".repeat(80) }
        settings.set(SourceKind.NOTES, true)
        val lines = EnabledSources(listOf(Fake(SourceKind.NOTES, emptyList(), long)), settings, { true }, Dispatchers.Unconfined).lines(null, 1L)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.joinToString("\n").length <= 1_500)
        assertEquals(long.take(lines.size), lines) // whole lines, kept in order
    }

    @Test fun orderIsCalendarNotesContactsLocationAndLinesCollapse() = runBlocking {
        SourceKind.entries.forEach { settings.set(it, true) }
        val fakes = listOf(
            Fake(SourceKind.LOCATION, emptyList(), listOf("place \u00b7 x")),
            Fake(SourceKind.CONTACTS, emptyList(), listOf("contact \u00b7 y")),
            Fake(SourceKind.NOTES, emptyList(), listOf("note \u00b7  a\n b ")),
            Fake(SourceKind.CALENDAR, emptyList(), listOf("event \u00b7 z")),
        )
        val lines = EnabledSources(fakes, settings, { true }, Dispatchers.Unconfined).lines("q", 1L)
        assertEquals(listOf("event \u00b7 z", "note \u00b7 a b", "contact \u00b7 y", "place \u00b7 x"), lines)
    }

    @Test fun nudgesPerDayOnlyTakesAllowedValues() {
        settings.setNudgesPerDay(3)
        assertEquals(3, settings.nudgesPerDay.value)
        settings.setNudgesPerDay(7)
        assertEquals(3, settings.nudgesPerDay.value)
        settings.setNudgesPerDay(0)
        assertEquals(0, SourceSettingsStore(ctx.getSharedPreferences("sources_test", Context.MODE_PRIVATE)).nudgesPerDay.value)
    }
}
