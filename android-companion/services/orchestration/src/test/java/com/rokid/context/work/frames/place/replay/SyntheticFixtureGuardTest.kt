package com.rokid.context.work.frames.place.replay

import com.rokid.context.memory.place.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The synthetic regression fixtures (the only replay data allowed in git). */
object SyntheticBundle {
    fun dir(): File = File(SyntheticBundle::class.java.getResource("/replay/synthetic")!!.toURI())
    fun load(): ReplayBundle = ReplayBundle.load(dir())
    const val ORIGIN_LAT = 10.0
    const val ORIGIN_LON = 20.0
    const val T0 = 1_000_000_000_000L
}

/** Fails if real trip data (photos, ids, names, coordinates) ever enters the repo's synthetic fixtures. Plan section 10. */
class SyntheticFixtureGuardTest {
    private val denyWords = listOf("salar", "golconda", "antera", "ramoji", "onegold", "hyderabad", "jubilee", "charminar", "bronze", "ivory", "founders", "air purifier")

    @Test fun syntheticFixturesHoldNoRealData() {
        val dir = SyntheticBundle.dir()
        val all = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue("fixtures exist", all.isNotEmpty())
        for (f in all) {
            assertTrue("only tsv in the synthetic folder: ${f.name}", f.extension == "tsv")
            assertFalse("no photo", f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "mp4", "db"))
            val text = f.readText().lowercase()
            for (w in denyWords) assertFalse("${f.name} holds a trip word '$w'", text.contains(w))
            assertFalse("${f.name} holds a real frame id", Regex("""\d{13}_rokid_""").containsMatchIn(text))
        }
        val b = SyntheticBundle.load()
        for (f in b.fixes) assertTrue("fix within 5 km of the invented origin", Geo.distanceM(f.lat, f.lon, SyntheticBundle.ORIGIN_LAT, SyntheticBundle.ORIGIN_LON) <= 5_000.0)
        for (p in b.pins) assertTrue("pin within 5 km of the invented origin", Geo.distanceM(p.lat, p.lon, SyntheticBundle.ORIGIN_LAT, SyntheticBundle.ORIGIN_LON) <= 5_000.0)
        for (s in b.sites) assertTrue(Geo.distanceM(s.lat, s.lon, SyntheticBundle.ORIGIN_LAT, SyntheticBundle.ORIGIN_LON) <= 5_000.0)
        for (f in b.frames) assertTrue("synthetic time base", f.createdAt >= SyntheticBundle.T0 - 3_600_000L && f.createdAt <= SyntheticBundle.T0 + 24 * 3_600_000L)
        assertTrue(b.frames.isNotEmpty() && b.pins.isNotEmpty())
        assertEquals(b.frames.size, b.frames.map { it.frameId }.toSet().size)
    }

    /** Nothing but the synthetic folder may live under test/resources/replay (a real bundle copied there would show up here). */
    @Test fun nothingButSyntheticLivesUnderReplayResources() {
        val replay = SyntheticBundle.dir().parentFile
        val others = replay.listFiles().orEmpty().filter { it.name != "synthetic" }
        assertTrue("unexpected replay resources: ${others.map { it.name }}", others.isEmpty())
    }

    /** The fixtures dir must not be next to anything else: the real bundle is never read from the classpath. */
    @Test fun loaderOnlyOpensTsvFiles() {
        assertEquals(setOf("tsv"), ReplayBundle.FILES.map { it.substringAfterLast('.') }.toSet())
    }
}
