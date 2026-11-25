package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import kotlinx.coroutines.runBlocking
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

/**
 * Retention P2 (Developer flag `retentionP2`, default OFF; design docs/superpowers/plans/2026-11-26-retention-p2.md). With the flag ON, photo expiry (linked photos, skipped photos, legacy orphan frames) leaves out a photo that a place card, a mark
 * or a pin points to, or that a place memory cites. With the flag OFF the selection is the old one, byte for byte. Text is never touched either way (RetentionNeverLosesTest holds that for the whole world; the snapshot below repeats it
 * for this one). The read-only preview shows counts only and writes nothing. Explicit Forget stays unchanged: it still removes an exempt photo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionP2Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val graph get() = MemoryGraph.get(ctx)
    private val now = System.currentTimeMillis()

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); DeveloperFlags.setPauseRetention(ctx, false); DeveloperFlags.setRetentionP2(ctx, false) }
    @After fun tearDown() { runBlocking { com.rokid.context.memory.MemorySettingsStore.get(ctx).update { MemorySettings() } }; DeveloperFlags.setRetentionP2(ctx, false); ForgetV2.testOverride = null; graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun fresh(v2: Boolean) { graph.db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = v2 }
    private fun bothModes(block: suspend (Boolean) -> Unit) = runBlocking { for (v2 in listOf(false, true)) { fresh(v2); block(v2) } }

    private fun settings() = MemorySettings(perKindSince = now - 60 * day)

    /** A linked photo: keyframe, EXTRACTED, described by a LONG caption (the `pl` photo of the golden world). */
    private suspend fun linked(fx: MomentForgetFixture, id: String, ts: Long) {
        fx.frame(id, ts, wire = "w$id", capturedAt = ts)
        graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "EXTRACTED", 0, null, null, null, ts)))
        graph.db.memoryDao().insert(MemoryEntity("cap$id", id, "caption $id", "caption", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, ts, false, "caption $id"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("cap$id", id, null, "FACT")))
    }
    private suspend fun skipped(fx: MomentForgetFixture, id: String, ts: Long) {
        fx.frame(id, ts, wire = "w$id", capturedAt = ts)
        graph.db.frameMetaDao().upsert(FrameMetaEntity(id, "w$id", null, "STILL", false, null, null, null, 640, 0.0f, null, null, null, "sp", ts, capturedAtMs = ts))
        graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "NEAR_DUP", 0, null, null, null, ts)))
    }

    /**
     * The world (one photo per day so no mark radius overlaps): ln = plain linked, lc = linked + place card, lm = linked + a mark in its radius, lp = linked + a place memory cites it, lpin = linked + a pin's Moment;
     * sn = plain skipped, sc = skipped + place card; on = legacy orphan, oc = legacy orphan + place card.
     */
    private suspend fun world(): MomentForgetFixture {
        val fx = MomentForgetFixture(graph.db, now)
        linked(fx, "ln", now - 10 * day); linked(fx, "lc", now - 11 * day); linked(fx, "lm", now - 12 * day); linked(fx, "lp", now - 13 * day); linked(fx, "lpin", now - 14 * day)
        fx.mark("mkM", now - 12 * day + 1000)
        graph.db.memoryDao().insert(MemoryEntity("placeLp", "lp", "a place", "place", "", MemoryTier.LONG, 0.9, ReviewState.OK, false, now - 13 * day, false, "a place"))
        skipped(fx, "sn", now - 20 * day); skipped(fx, "sc", now - 21 * day)
        fx.frame("on", now - 40 * day); fx.frame("oc", now - 41 * day)
        return fx
    }
    private val outside = setOf("lc", "lpin", "sc", "oc")
    private val allIds = listOf("ln", "lc", "lm", "lp", "lpin", "sn", "sc", "on", "oc")

    private fun retention(exempt: Boolean, outsideSet: Set<String>? = outside) = Retention(graph, ForgetService(graph.db, graph.proposals) { now }, protectedFrames = { outsideSet }, exemptPhotos = exempt)
    private suspend fun run(exempt: Boolean, outsideSet: Set<String>? = outside) = retention(exempt, outsideSet).run(settings(), now) { r -> FrameSweep.delete(r.framesToDelete) }
    private suspend fun alive() = allIds.filter { graph.db.forgetDao().frame(it) != null }

    private fun dump(table: String, order: String): String = graph.db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $order").use { c ->
        buildString { while (c.moveToNext()) { for (i in 0 until c.columnCount) append(if (c.getType(i) == android.database.Cursor.FIELD_TYPE_BLOB) "<blob>" else c.getString(i)).append('|'); append('\n') } }
    }
    private val textTables = listOf("memory" to "id", "frame_source" to "memoryId, frameId, role", "ask" to "id", "nudge" to "id", "moment" to "momentId", "moment_part" to "momentId, kind, refId", "scene" to "sceneId")
    private fun snapshot() = textTables.associate { (t, o) -> t to dump(t, o) }

    /** Flag-off parity: with the flag OFF the pass is the old one even when a place-card / pin set is available: the same photos expire as with the default Retention, and the Plan counts are equal. */
    @Test fun flagOffIsTheOldRetentionByteForByte() = bothModes { v2 ->
        assertFalse("the flag defaults to OFF", DeveloperFlags.retentionP2(ctx))
        world()
        val planOff = retention(exempt = false).plan(settings(), now)
        val planDefault = Retention(graph, ForgetService(graph.db, graph.proposals) { now }).plan(settings(), now)
        assertEquals("v2=$v2", planDefault, planOff)
        val summary = run(exempt = false)
        assertEquals("every due photo expires, the card / mark / pin / place ones too", emptyList<String>(), alive())
        assertEquals(2, summary.skippedDeleted)
    }

    @Test fun flagOffWorkerAlsoExpiresEverything() = runBlocking {
        ForgetV2.testOverride = false
        world()
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { settings().copy(perKindLastRun = now) }
        assertTrue(androidx.work.testing.TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is androidx.work.ListenableWorker.Result.Success)
        assertEquals(emptyList<String>(), alive())
    }

    /** Flag ON: the place-card, mark, place-memory and pin photos stay (linked, skipped and orphan kinds); the others expire exactly as before; text is byte-equal. */
    @Test fun flagOnKeepsPlaceCardMarkPlaceMemoryAndPinPhotos() = bothModes { v2 ->
        world()
        val text = snapshot()
        val plan = retention(exempt = true).plan(settings(), now)
        assertEquals("linked: ln only", 1, plan.photosDue); assertEquals("skipped: sn only", 1, plan.skippedPhotosDue); assertEquals("orphans: on only", 1, plan.legacyDue)
        run(exempt = true)
        assertEquals("v2=$v2", listOf("lc", "lm", "lp", "lpin", "sc", "oc"), alive())
        listOf("ln", "sn", "on").forEach { assertNull("$it expired", graph.db.forgetDao().frame(it)) }
        assertEquals("text is untouched", text, snapshot())
        run(exempt = true) // a second pass changes nothing and does not loop
        assertEquals(listOf("lc", "lm", "lp", "lpin", "sc", "oc"), alive())
    }

    /** An exempt photo is still removed by an explicit user Forget (the exemption is retention only). */
    @Test fun aUserForgetStillRemovesAnExemptPhoto() = bothModes { v2 ->
        world(); run(exempt = true)
        assertNotNull(graph.db.forgetDao().frame("lc"))
        ForgetService(graph.db, graph.proposals).forgetMoment("lc")
        assertNull("v2=$v2", graph.db.forgetDao().frame("lc"))
        assertEquals(listOf("lc"), graph.db.forgetDao().tombstoned(listOf("lc")))
    }

    /** Starvation: 450 exempt photos at the head of the oldest-first list never hide the 3 that may expire, and the pass terminates. */
    @Test fun manyExemptPhotosNeverStarveTheRestOrLoop() = runBlocking {
        ForgetV2.testOverride = false
        val fx = MomentForgetFixture(graph.db, now)
        val kept = (1..450).map { "k$it" }.toSet()
        kept.forEachIndexed { i, id -> linked(fx, id, now - 400 * day - i * hour) }
        (1..3).forEach { linked(fx, "d$it", now - 20 * day - it * hour) }
        run(exempt = true, outsideSet = kept)
        assertEquals(450, kept.count { graph.db.forgetDao().frame(it) != null })
        assertEquals(0, (1..3).count { graph.db.forgetDao().frame("d$it") != null })
    }

    /** Fail closed: with the flag ON and the place-card / pin set unreadable, no photo expires (flag OFF it would). */
    @Test fun anUnreadableExemptionSetExpiresNothing() = runBlocking {
        ForgetV2.testOverride = false
        world()
        run(exempt = true, outsideSet = null)
        assertEquals(allIds, alive())
        run(exempt = true) // readable again: only the non-exempt ones expire
        assertEquals(listOf("lc", "lm", "lp", "lpin", "sc", "oc"), alive())
    }

    /** The preview is the plan at now + 24 h and now + 7 d, counts only, and writes nothing. */
    @Test fun thePreviewShowsCountsByKindAndWritesNothing() = runBlocking {
        ForgetV2.testOverride = false
        val fx = MomentForgetFixture(graph.db, now)
        linked(fx, "soon", now - 6 * day - 2 * hour) // 7 d deadline in about 22 h
        linked(fx, "later", now - 3 * day)            // deadline in 4 days
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { settings() }
        val before = snapshot(); val metaBefore = dump("canvas_meta", "key"); val frames = dump("frame", "id")
        val p = RetentionPreview.compute(ctx, now)!!
        assertEquals(1, p.day.photos); assertEquals(2, p.week.photos)
        assertEquals(0, p.day.skippedPhotos); assertEquals(0, p.day.imageVectors)
        assertFalse(p.exemptActive); assertFalse(p.paused)
        assertEquals(before, snapshot()); assertEquals(metaBefore, dump("canvas_meta", "key")); assertEquals(frames, dump("frame", "id"))
        val text = p.lines().joinToString(" ")
        assertTrue(text, "Next 24 hours: 1 photos" in text && "Next 7 days: 2 photos" in text)
        listOf("soon", "later", "caption").forEach { assertFalse("no id or text: $text", it in text) }
    }

    @Test fun thePreviewHonoursTheFlagAndMentionsPause() = runBlocking {
        ForgetV2.testOverride = false
        val fx = MomentForgetFixture(graph.db, now)
        linked(fx, "a", now - 10 * day); linked(fx, "b", now - 11 * day)
        com.rokid.context.memory.MemorySettingsStore.get(ctx).update { settings() }
        assertEquals(2, RetentionPreview.compute(ctx, now)!!.day.photos)
        DeveloperFlags.setRetentionP2(ctx, true); DeveloperFlags.setPauseRetention(ctx, true)
        val p = RetentionPreview.compute(ctx, now)!!
        assertTrue(p.exemptActive); assertTrue(p.paused)
        assertTrue(p.lines().any { "Pause clean-up is on" in it }); assertTrue(p.lines().any { "place card, a mark or a pin" in it })
        assertEquals(0, RetentionPreview.Counts(0, 0, 0, 0).total)
        assertEquals("Next 24 hours: nothing is due.", RetentionPreview(false, false, RetentionPreview.Counts(0, 0, 0, 0), RetentionPreview.Counts(1, 0, 0, 0)).lines().first())
    }
}
