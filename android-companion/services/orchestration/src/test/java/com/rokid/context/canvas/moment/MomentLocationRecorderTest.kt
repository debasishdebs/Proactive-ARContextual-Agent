package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.orchestration.forget.DeveloperFlags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentLocationRecorderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val fx = MomentFixture(db)
    private val dao = db.momentLocationDao()
    private val logs = mutableListOf<String>()
    @After fun close() = db.close()

    private val now = MS + 10_000          // the wall clock of every poke
    private val elapsedNow = 5_000_000L    // the elapsed clock of every poke
    private val id = MomentId.format(SEC + 5)
    private val capture = MS + 5_000

    /** A fix [ageMs] old at poke time, on the elapsed clock. */
    private fun elapsedFix(ageMs: Long, acc: Float? = 10f, lat: Double = 12.0) = HeldFix(lat, 77.0, acc, elapsedNow - ageMs, elapsedClock = true)
    private fun wallFix(wallMs: Long, acc: Float? = 10f, lat: Double = 13.0) = HeldFix(lat, 77.0, acc, wallMs, elapsedClock = false)

    private fun rec(
        enabled: Boolean = true, zoneBlocked: Boolean = false, zones: suspend () -> List<PauseZone>? = { emptyList() },
        zoneFix: HeldFix? = null, placeFix: HeldFix? = null, lastKnown: HeldFix? = null, generation: () -> Long = { 0 },
    ) = MomentLocationRecorder(db, { now }, { elapsedNow }, { enabled }, { zoneBlocked }, zones, { zoneFix }, { placeFix }, { lastKnown }, generation) { logs += it }

    private suspend fun seedFrame() = fx.frame("f1", capture)

    @Test fun onByDefault() {
        assertTrue(DeveloperFlags.momentLocation(ctx))
        ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(DeveloperFlags.momentLocation(ctx))
        DeveloperFlags.setMomentLocation(ctx, false); assertFalse(DeveloperFlags.momentLocation(ctx))
        DeveloperFlags.setMomentLocation(ctx, true); assertTrue(DeveloperFlags.momentLocation(ctx))
    }

    @Test fun offWritesNothing() = runBlocking {
        seedFrame()
        assertEquals(0, rec(enabled = false, zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
        assertTrue(logs.single().contains("skipped=off:1"))
    }

    @Test fun zoneStateRefuses() = runBlocking {
        seedFrame()
        assertEquals(0, rec(zoneBlocked = true, zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
        assertTrue(logs.single().contains("zone_state:1"))
    }

    @Test fun fixInsideZoneNeverSaved() = runBlocking {
        seedFrame()
        val zone = PauseZone("z", "Home", 11.99, 76.99, 12.01, 77.01)
        assertEquals(0, rec(zones = { listOf(zone) }, zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
        assertTrue(logs.single().contains("zone_point:1"))
        // an unreadable zone list blocks too
        assertEquals(0, rec(zones = { null }, zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
    }

    @Test fun zoneActiveAfterEntryRefusesTheWrite() = runBlocking {
        seedFrame()
        var reads = 0 // the entry check sees no zone; every later read (inside the write transaction) sees one
        val r = MomentLocationRecorder(db, { now }, { elapsedNow }, { true }, { reads++ > 0 }, { emptyList() }, { elapsedFix(1000) }, { null }, { null }, { 0 }) { logs += it }
        assertEquals(0, r.poke())
        assertEquals(0, dao.count()); assertTrue(logs.single().contains("zone_state:1"))
    }

    @Test fun pausedSecondRefused() = runBlocking {
        seedFrame()
        db.pauseDao().insertInterval(PauseIntervalEntity("manual:1", "PHONE", capture - 1000, null))
        assertEquals(0, rec(zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
        assertTrue(logs.single().contains("paused:1"))
    }

    @Test fun staleFixSkipped61s() = runBlocking {
        seedFrame()
        // capture is 5 s before now; a fix 66 s old is 61 s before the capture
        assertEquals(0, rec(zoneFix = elapsedFix(66_000)).poke()); assertEquals(0, dao.count())
        assertTrue(logs.single().contains("no_fix:1"))
        // 65 s old = exactly 60 s before the capture: kept
        assertEquals(1, rec(zoneFix = elapsedFix(65_000)).poke()); assertEquals(60_000L, dao.of(listOf(id)).single().fixAgeMs)
    }

    @Test fun inaccurateFixSkipped101m() = runBlocking {
        seedFrame()
        assertEquals(0, rec(zoneFix = elapsedFix(1000, acc = 100.5f)).poke()); assertEquals(0, rec(zoneFix = elapsedFix(1000, acc = 101f)).poke())
        assertEquals(0, dao.count())
        assertEquals(1, rec(zoneFix = elapsedFix(1000, acc = 100f)).poke())
    }

    @Test fun nullAccuracySkipped() = runBlocking {
        seedFrame()
        assertEquals(0, rec(zoneFix = elapsedFix(1000, acc = null), placeFix = elapsedFix(1000, acc = Float.NaN), lastKnown = wallFix(capture, acc = 0f)).poke())
        assertEquals(0, dao.count())
    }

    @Test fun zoneFixPreferredOverPlaceOverLastKnown() = runBlocking {
        seedFrame()
        assertEquals(1, rec(zoneFix = elapsedFix(1000, lat = 1.0), placeFix = elapsedFix(1000, lat = 2.0), lastKnown = wallFix(capture, lat = 3.0)).poke())
        assertEquals("ZONE_STREAM", dao.of(listOf(id)).single().source); dao.deleteAllMomentLocations()
        assertEquals(1, rec(zoneFix = null, placeFix = elapsedFix(1000, lat = 2.0), lastKnown = wallFix(capture, lat = 3.0)).poke())
        assertEquals("PLACE_GUIDE", dao.of(listOf(id)).single().source); dao.deleteAllMomentLocations()
        // a stale zone fix falls through to the next source
        assertEquals(1, rec(zoneFix = elapsedFix(90_000, lat = 1.0), placeFix = null, lastKnown = wallFix(capture + 30_000, lat = 3.0)).poke())
        val r = dao.of(listOf(id)).single(); assertEquals("LAST_KNOWN", r.source); assertEquals(30_000L, r.fixAgeMs); assertEquals(3.0, r.lat, 0.0)
        assertNull(r.speedClass); assertNull(r.method); assertEquals("FRAME", r.anchorKind); assertEquals("f1", r.anchorId); assertEquals(capture, r.atMs)
    }

    @Test fun aPlaceFixOfAnotherGenerationIsNeverUsed() = runBlocking {
        seedFrame()
        assertEquals(0, rec(placeFix = elapsedFix(1000).copy(generation = 3), generation = { 4 }).poke())
        assertEquals(1, rec(placeFix = elapsedFix(1000).copy(generation = 4), generation = { 4 }).poke())
    }

    @Test fun tombstonedRefused() = runBlocking {
        seedFrame(); db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1))
        assertEquals(0, rec(zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count()); assertTrue(logs.single().contains("tombstoned:1"))
        logs.clear(); db.forgetDao().let { }
        fx.frame("f2", capture + 100); db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id, 1)))
        assertEquals(0, rec(zoneFix = elapsedFix(1000)).poke()); assertEquals(0, dao.count())
    }

    @Test fun authorityChangedRefused() = runBlocking {
        seedFrame()
        assertEquals(0, rec(zones = { db.canvasSlice3Dao().bumpFrameForgetRev(); emptyList() }, zoneFix = elapsedFix(1000)).poke())
        assertEquals(0, dao.count()); assertTrue(logs.single().contains("authority:1"))
        var gen = 0L
        logs.clear()
        assertEquals(0, rec(zones = { gen = 1; emptyList() }, zoneFix = elapsedFix(1000), generation = { gen }).poke()); assertEquals(0, dao.count())
    }

    @Test fun anchorGoneRefused() = runBlocking {
        seedFrame()
        assertEquals(0, rec(zones = { db.openHelper.writableDatabase.execSQL("DELETE FROM frame_meta"); emptyList() }, zoneFix = elapsedFix(1000)).poke())
        assertEquals(0, dao.count()); assertTrue(logs.single().contains("gone:1"))
    }

    @Test fun secondPokeWritesNothing() = runBlocking {
        seedFrame()
        val r = rec(zoneFix = elapsedFix(1000))
        assertEquals(1, r.poke()); assertEquals(0, r.poke()); assertEquals(1, dao.count())
        // and a later, better fix never overwrites the first
        assertEquals(0, rec(zoneFix = elapsedFix(500, lat = 99.0)).poke()); assertEquals(12.0, dao.of(listOf(id)).single().lat, 0.0)
    }

    @Test fun aMarkTakesAFixAndTheIntentRowDoesNot() = runBlocking {
        fx.mark("L1", capture); fx.mark("L1:i", capture + 1500, kind = "INTENT")
        assertEquals(1, rec(zoneFix = elapsedFix(1000)).poke())
        val r = dao.of(listOf(id)).single(); assertEquals("MARK", r.anchorKind); assertEquals("L1", r.anchorId)
    }

    @Test fun logLineHasNoIdsOrCoordinates() = runBlocking {
        seedFrame(); rec(zoneFix = elapsedFix(1000, lat = 12.3456)).poke()
        val line = logs.single(); assertEquals("moment loc poke saved=1 skipped=none", line)
        assertFalse(line.contains("12.3") || line.contains(id) || line.contains("f1"))
    }

    @Test fun rebuildKeepsRow() = runBlocking<Unit> {
        fx.frame("f1", capture); fx.memory("m1", "f1")
        val b = MomentBuilder(db, enabled = { true }, clock = { 5 })
        b.rebuildWindow(MS, MS + 20_000)
        assertEquals(1, rec(zoneFix = elapsedFix(1000)).poke())
        b.rebuildWindow(MS, MS + 20_000)
        db.momentDao().remove(db.momentDao().allMoments().map { it.momentId }) // a builder removal (step build) never deletes it
        b.rebuildWindow(MS, MS + 20_000)
        assertEquals(1, dao.count())
    }

    @Test fun builderOutputIdenticalWithAndWithoutRow() = runBlocking<Unit> {
        fx.frame("f1", capture); fx.memory("m1", "f1"); fx.mark("L1", capture + 300)
        val b = MomentBuilder(db, enabled = { true }, clock = { 5 })
        b.rebuildWindow(MS, MS + 20_000); val without = fx.snapshot()
        assertEquals(1, rec(zoneFix = elapsedFix(1000)).poke())
        b.rebuildWindow(MS, MS + 20_000)
        assertEquals(without, fx.snapshot())
        val resolved = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao())).resolve(listOf(id)).single().contentHash
        assertEquals(db.momentDao().moment(id)!!.contentHash, resolved)
        dao.deleteAllMomentLocations(); b.rebuildWindow(MS, MS + 20_000)
        assertEquals(without, fx.snapshot())
    }

    @Test fun recorderIdMatchesBuilderId() = runBlocking<Unit> {
        fx.frame("f1", capture); fx.mark("L1", capture + 7000); fx.frame("f2", capture - 2500); fx.memory("m1", "f1"); fx.memory("m2", "f2")
        MomentBuilder(db, enabled = { true }, clock = { 5 }).rebuildWindow(MS, MS + 20_000)
        val built = db.momentDao().allMoments().map { it.momentId }.toSet()
        assertEquals(3, built.size)
        // the recorder is poked with the fix near the first photo, 120 s lookback covers all three
        assertEquals(3, rec(zoneFix = elapsedFix(3000)).poke())
        assertEquals(built, db.momentLocationDao().count().let { db.openHelper.readableDatabase.query("SELECT momentId FROM moment_location").use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() } })
    }
}
