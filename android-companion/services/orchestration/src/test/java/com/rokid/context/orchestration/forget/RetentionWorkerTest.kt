package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.MemorySettings
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.work.OrchestrationScheduler
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
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    }

    @After fun tearDown() { AppConfigStore.setPipelineEnabled(ctx, true); graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, tier: MemoryTier, ageMs: Long, frameId: String? = null, review: ReviewState = ReviewState.OK) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "text $id", "note", "", tier, 0.8, review, false, now - ageMs, false, "text $id"))
    private fun work() = runBlocking { TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() }
    private suspend fun exists(id: String) = graph.db.memoryViewDao().byId(id) != null

    /** Defaults: SHORT 24 h, MEDIUM 7 d, LONG 30 d (MemorySettings). */
    @Test fun tiersUseTheirWindows() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour); mem("s_new", MemoryTier.SHORT, 23 * hour)
        mem("m_kept", MemoryTier.MEDIUM, 6 * 24 * hour); mem("m_old", MemoryTier.MEDIUM, 8 * 24 * hour, review = ReviewState.NEEDS_REVIEW)
        mem("l_old", MemoryTier.LONG, 31 * 24 * hour); mem("l_new", MemoryTier.LONG, 29 * 24 * hour)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertFalse(exists("s_old")); assertTrue(exists("s_new"))
        assertTrue(exists("m_kept")); assertFalse(exists("m_old"))
        assertFalse(exists("l_old")); assertTrue(exists("l_new"))
    }

    @Test fun runsWhileCaptureIsPaused() = runBlocking {
        AppConfigStore.setPipelineEnabled(ctx, false)
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        work()
        assertFalse(exists("s_old"))
    }

    @Test fun oldFrameWithoutMemoriesDeleted() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/old.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val kept = File(ctx.filesDir, "rokid_archive/new.jpg").apply { writeBytes(byteArrayOf(2)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f_old", now - 31 * 24 * hour, jpg.absolutePath, "rokid_glasses"))
        graph.db.frameDao().insertIgnore(FrameEntity("f_new", now - hour, kept.absolutePath, "rokid_glasses"))
        work()
        assertNull(graph.db.forgetDao().frame("f_old"))
        assertFalse(jpg.exists())
        assertTrue(kept.exists() && graph.db.forgetDao().frame("f_new") != null)
    }

    @Test fun lastExpiredMemoryOfAFrameDeletesItsJpegAndLocksIt() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/f1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f1", now - 2 * hour, jpg.absolutePath, "rokid_glasses"))
        mem("s_old", MemoryTier.SHORT, 25 * hour, frameId = "f1")
        work()
        assertFalse(jpg.exists())
        assertEquals(listOf("f1"), graph.db.forgetDao().tombstoned(listOf("f1")))
    }

    @Test fun redactsAsksCitingExpiredMemory() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        graph.db.askDao().upsert(AskEntity("q1", 1, "q", "answer", """["s_old"]""", null, "phone_typed", false))
        work()
        val ask = graph.db.askDao().byId("q1")!!
        assertTrue(ask.redacted); assertEquals(ForgetService.REDACTED, ask.answer)
    }

    @Test fun ensureRetentionSchedulesOneDailyJob() {
        OrchestrationScheduler(ctx).scheduleCritic()
        OrchestrationScheduler(ctx).scheduleCritic()
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(RetentionWorker.NAME).get()
        assertEquals(1, infos.size)
        // A pause cancels the pipeline's periodic work, never retention (it only removes data).
        OrchestrationScheduler(ctx).pausePipeline()
        assertFalse(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(RetentionWorker.NAME).get().single().state.isFinished)
    }

    /** Fix round 1 C1: expiry never invalidates descendants; a MEDIUM summary outlives its SHORT sources. */
    @Test fun summaryOutlivesItsShortSources() = runBlocking {
        mem("src", MemoryTier.SHORT, 25 * hour)
        mem("sum_young", MemoryTier.MEDIUM, 25 * hour)
        mem("sum_old", MemoryTier.MEDIUM, 8 * 24 * hour)
        graph.db.forgetDao().insertEdges(listOf(com.rokid.context.memory.db.MemorySourceEntity("sum_young", "src"), com.rokid.context.memory.db.MemorySourceEntity("sum_old", "src")))
        work()
        assertFalse(exists("src"))
        assertEquals(ReviewState.OK, graph.db.memoryViewDao().byId("sum_young")!!.reviewState)
        assertFalse(exists("sum_old"))
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().isEmpty())
    }

    /** Fix round 1 I3: retention also sweeps JPEGs of tombstoned frames left behind by an earlier failed delete. */
    @Test fun retentionSweepsOrphanJpegsOfTombstonedFrames() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/f_gone.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f_gone", 1))
        work()
        assertFalse(jpg.exists())
    }

    /** Final review C1: an accepted reminder whose cited memory expires keeps its params and its armed alarm. */
    private suspend fun acceptedReminder(askId: String, source: String): Pair<String, org.robolectric.shadows.ShadowAlarmManager> {
        graph.db.askDao().upsert(AskEntity(askId, 1, "q", "answer", "[\"$source\"]", null, "phone_typed", false))
        val pid = "prop_ASK_$askId"
        val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", now + 72 * hour, "about the results")
        graph.proposals.addIgnore(
            com.rokid.context.memory.db.ProposalEntity(pid, "ASK", askId, "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "hud", "alarm:$pid", 1),
        )
        graph.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity("pend_$askId", "ASK", askId, "SHOPPING", "{\"items\":[\"milk\"]}", "PENDING", Long.MAX_VALUE, null, null, 1))
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        return pid to alarms
    }

    @Test fun retentionKeepsAnAcceptedRemindersAlarmAndParams() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        val (pid, alarms) = acceptedReminder("qa", "s_old")
        val params = graph.proposals.byId(pid)!!.paramsJson
        work()
        assertFalse(exists("s_old"))
        assertTrue(graph.db.askDao().byId("qa")!!.redacted)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals("EXECUTED", graph.proposals.byId(pid)!!.state)
        assertEquals(params, graph.proposals.byId(pid)!!.paramsJson)
        // a PENDING proposal of the same ask still expires and loses its text (spec 6.1.1: PENDING -> EXPIRED)
        assertEquals("EXPIRED", graph.proposals.byId("pend_qa")!!.state)
        assertEquals(ForgetService.REDACTED_PARAMS, graph.proposals.byId("pend_qa")!!.paramsJson)
    }

    @Test fun aUserForgetOfTheSameSourceStillCancelsIt() = runBlocking {
        mem("s_young", MemoryTier.SHORT, 1 * hour)
        val (pid, alarms) = acceptedReminder("qb", "s_young")
        assertTrue(ForgetActions.forgetFact(ctx, "s_young"))
        assertEquals(0, alarms.scheduledAlarms.size)
        assertEquals(ForgetService.REDACTED_PARAMS, graph.proposals.byId(pid)!!.paramsJson)
    }

    // --- Task 8: per-kind retention (spec P6; Rulings 8, 11-14) --------------------------------------------------------
    private val day = 24 * hour
    private val t0 = now // perKindSince in the per-kind tests: the first run of this build

    private fun settings(since: Long = t0, photo: Int = 7, skipped: Int = 1, caption: Int = MemorySettings.FOREVER, summary: Int = MemorySettings.FOREVER, vec: Int = MemorySettings.FOREVER) =
        MemorySettings(photoDays = photo, skippedPhotoDays = skipped, captionDays = caption, clusterSummaryDays = summary, imageVectorDays = vec, perKindSince = since)

    /** One retention pass at [at], deleting the listed files like the worker does. */
    private fun pass(s: MemorySettings, at: Long, gate: Boolean = false) = runBlocking {
        Retention(graph, ForgetService(graph.db, graph.proposals) { at }, describeBeforeDeleteReady = gate).run(s, at) { r -> FrameSweep.delete(r.framesToDelete) }
    }

    private suspend fun photo(
        id: String, ts: Long, state: String, salience: Float? = null, dwell: Boolean = false, clusterId: String? = null,
        updatedAt: Long = ts, lastError: String? = null, linked: Boolean = false, space: String? = "sp",
    ): File {
        val dir = File(ctx.filesDir, "rokid_archive").apply { mkdirs() }
        val jpg = File(dir, "$id.jpg").apply { writeBytes(byteArrayOf(1)) }
        File(dir, "$id.meta.json").writeText("{}")
        val db = graph.db
        db.frameDao().insertIgnore(FrameEntity(id, ts, jpg.absolutePath, "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", dwell, null, null, null, 640, salience, null, clusterId, null, space, ts))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, 0, lastError, null, null, updatedAt)))
        db.frameVectorDao().upsert(listOf(FrameVectorEntity(id, "sp", byteArrayOf(1, 2))))
        if (linked) {
            db.memoryDao().insert(MemoryEntity("m_$id", id, "t", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, ts, false, "t"))
            db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m_$id", id, clusterId, "FACT")))
        }
        return jpg
    }

    private suspend fun cluster(id: String, state: String) =
        graph.db.frameClusterDao().upsert(FrameClusterEntity(id, 1, 2, "gap", "[]", null, state = state))
    private suspend fun frameRow(id: String) = graph.db.forgetDao().frame(id)

    @Test fun skippedPhotoDeletedAfterOneDay() = runBlocking {
        cluster("c1", "SUMMARIZED")
        val old = t0 + 30 * 60_000L // captured after the stamp: the 7-day protection of pre-existing photos does not apply
        val shed = photo("shed", old, "NEAR_DUP", lastError = "shed_backlog", updatedAt = old)
        val dup = photo("dup", old, "NEAR_DUP", salience = 0.1f)
        val member = photo("mem", old, "CLUSTERED", clusterId = "c1").also {
            graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m_sum", "mem", "c1", "CLUSTER_MEMBER")))
        }
        mem("m_sum", MemoryTier.LONG, day)
        pass(settings(), t0 + 12 * hour)
        assertTrue(shed.exists() && dup.exists() && member.exists())
        val summary = pass(settings(), t0 + 25 * hour)
        assertNull(frameRow("shed")); assertNull(frameRow("dup")); assertNull(frameRow("mem"))
        assertFalse(shed.exists() || dup.exists() || member.exists())
        assertFalse(File(shed.parentFile, "shed.meta.json").exists()) // the sidecar goes too
        assertEquals(3, summary.skippedDeleted)
        // meta, vector and state stay; the tombstone blocks re-extraction
        assertNotNull(graph.db.frameMetaDao().get("shed")); assertNotNull(graph.db.frameStateDao().get("dup"))
        assertEquals(3, graph.db.frameVectorDao().countForSpace("sp"))
        assertEquals(listOf("shed", "dup", "mem").sorted(), graph.db.forgetDao().tombstoned(listOf("shed", "dup", "mem")).sorted())
        assertTrue(exists("m_sum"))
    }

    @Test fun clusterRepNotTreatedAsSkipped() = runBlocking {
        cluster("c1", "SUMMARIZED")
        val old = t0 - 10 * day
        val rep = photo("rep", old, "CLUSTERED", clusterId = "c1")
        mem("m_sum", MemoryTier.LONG, day, frameId = "rep")
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m_sum", "rep", "c1", "CLUSTER_REP")))
        pass(settings(), t0 + 3 * day)
        assertTrue(rep.exists() && frameRow("rep") != null)
        pass(settings(), t0 + 8 * day) // follows the 7-day photo rule instead
        assertNull(frameRow("rep")); assertTrue(exists("m_sum"))
    }

    @Test fun promotedKeyMomentFollowsPhotoRetention() = runBlocking {
        val ts = t0 - day
        val salient = photo("salient", ts, "NEAR_DUP", salience = 0.7f, linked = true)
        val dwell = photo("dwell", ts, "NEAR_DUP", dwell = true, linked = true)
        pass(settings(), t0 + 3 * day)
        assertTrue(salient.exists() && dwell.exists())
        pass(settings(), t0 + 8 * day)
        assertNull(frameRow("salient")); assertNull(frameRow("dwell"))
        assertTrue(exists("m_salient") && exists("m_dwell"))
    }

    @Test fun zeroDaySkippedDeletesThisRun() = runBlocking {
        val dup = photo("dup", t0, "NEAR_DUP", salience = 0.0f)
        val kept = photo("ex", t0 - day, "EXTRACTED", linked = true)
        pass(settings(skipped = 0), t0)
        assertFalse(dup.exists()); assertNull(frameRow("dup"))
        assertTrue(kept.exists())
    }

    @Test fun linkedPhotoDeletedAtSevenDays() = runBlocking {
        val f = photo("f", t0 + hour, "EXTRACTED", linked = true)
        pass(settings(), t0 + 6 * day)
        assertTrue(f.exists())
        pass(settings(), t0 + 8 * day + hour)
        assertFalse(f.exists()); assertNull(frameRow("f"))
        assertTrue(exists("m_f"))
        assertEquals(1, graph.db.frameVectorDao().countForSpace("sp"))
        assertEquals("m_f", graph.db.frameSourceDao().memoriesForFrame("f").single())
    }

    /** Spec P6.4: with the charger never connected, an unlinked photo is still deleted on day 3 of its grace. */
    @Test fun unlinkedPhotoGetsThreeDayGraceWithChargerNeverConnected() = runBlocking {
        val f = photo("u", t0 + hour, "EXTRACTED")
        pass(settings(), t0 + 8 * day, gate = true) // past 7 days, inside the grace
        assertTrue(f.exists())
        pass(settings(), t0 + 9 * day + 12 * hour, gate = true)
        assertTrue(f.exists())
        val s = pass(settings(), t0 + 10 * day + 2 * hour, gate = true)
        assertFalse(f.exists()); assertNull(frameRow("u"))
        assertEquals(1, s.deletedUndescribed)
    }

    @Test fun captionsAndSummariesOutliveTierWindows() = runBlocking {
        graph.db.memoryDao().insert(MemoryEntity("cap", null, "t", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, now - 400 * day, false, "t"))
        graph.db.memoryDao().insert(MemoryEntity("sum", null, "t", "cluster_summary", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, now - 400 * day, false, "t"))
        graph.db.memoryDao().insert(MemoryEntity("capS", null, "t", "caption", "", MemoryTier.SHORT, 1.0, ReviewState.OK, false, now - 400 * day, false, "t"))
        mem("plain", MemoryTier.LONG, 31 * day)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertTrue(exists("cap") && exists("sum") && exists("capS"))
        assertFalse(exists("plain"))
    }

    @Test fun captionRetentionHonoured() = runBlocking {
        fun m(id: String, type: String, age: Long) = MemoryEntity(id, null, "t", type, "", MemoryTier.LONG, 1.0, ReviewState.OK, false, t0 - age, false, "t")
        graph.db.memoryDao().insert(m("c_old", "caption", 31 * day)); graph.db.memoryDao().insert(m("c_new", "caption", 29 * day))
        graph.db.memoryDao().insert(m("s_old", "cluster_summary", 91 * day)); graph.db.memoryDao().insert(m("s_new", "cluster_summary", 89 * day))
        pass(settings(caption = 30, summary = 90), t0)
        assertFalse(exists("c_old")); assertTrue(exists("c_new"))
        assertFalse(exists("s_old")); assertTrue(exists("s_new"))
        pass(settings(), t0 + 1000 * day) // FOREVER: never
        assertTrue(exists("c_new") && exists("s_new"))
    }

    @Test fun imageVectorRetention() = runBlocking {
        val old = photo("old", t0 - 40 * day, "EXTRACTED", linked = true)
        photo("new", t0 - 5 * day, "EXTRACTED", linked = true)
        pass(settings(vec = 30), t0)
        assertNull(graph.db.frameVectorDao().get("old", "sp")); assertNotNull(graph.db.frameVectorDao().get("new", "sp"))
        assertTrue(old.exists() && frameRow("old") != null) // the photo itself is not touched by the vector knob
        // the backfill must not re-embed what retention removed
        assertFalse("old" in graph.db.embeddingDao().missingImageIds("sp", 10))
        pass(settings(), t0 + 1000 * day) // FOREVER
        assertNotNull(graph.db.frameVectorDao().get("new", "sp"))
    }

    /** Ruling 13: the ~800 trip photos survive the first run; their deadlines count from the first run. */
    @Test fun upgradeGraceProtectsOldPhotos() = runBlocking {
        val trip = t0 - 20 * day
        cluster("cs", "SUMMARIZED")
        repeat(300) { photo("lk$it", trip, "EXTRACTED", linked = true) }
        repeat(300) { photo("sk$it", trip, "NEAR_DUP", salience = 0.1f) }
        repeat(200) { photo("ul$it", trip, "EXTRACTED") }
        // first run of this build, through the worker: nothing at all is deleted, the stamp is recorded
        val store = MemorySettingsStore.get(ctx)
        store.update { it.copy(perKindSince = 0L) } // the store is a process singleton: an earlier test's run may have stamped it
        assertEquals(0L, store.settings.value.perKindSince)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertEquals(800, graph.db.frameDao().count())
        assertTrue(store.settings.value.perKindSince >= t0)
        val since = store.settings.value.perKindSince
        val s = store.settings.value
        pass(s, since + 6 * hour); assertEquals(800, graph.db.frameDao().count())
        pass(s, since + 25 * hour); assertEquals(800, graph.db.frameDao().count()) // pre-existing photos: 7 days of protection, skipped ones too
        pass(s, since + 6 * day); assertEquals(800, graph.db.frameDao().count())
        pass(s, since + 7 * day + hour); assertEquals(200, graph.db.frameDao().count()) // skipped and linked go; unlinked wait for describe-before-delete
        pass(s, since + 30 * day); assertEquals(200, graph.db.frameDao().count()) // gate off: undescribed photos are never deleted
        pass(s, since + 10 * day + hour, gate = true); assertEquals(0, graph.db.frameDao().count())
        assertEquals(300, graph.db.memoryViewDao().let { dao -> (0 until 300).count { dao.byId("m_lk$it") != null } })
        // the stamp is written once
        work(); assertEquals(since, store.settings.value.perKindSince)
    }

    /** A frame whose cluster is not summarised yet (or is being retried) is never expired, however old. */
    @Test fun unsummarisedClusterFrameNeverExpired() = runBlocking {
        cluster("cOpen", "CLOSED"); cluster("cFail", "FAILED"); cluster("cSum", "SUMMARIZING")
        val old = t0 - 100 * day
        val files = listOf(
            photo("a", old, "IN_CLUSTER", clusterId = "cOpen"),
            photo("b", old, "CLUSTERED", clusterId = "cFail", salience = 0.0f),
            photo("c", old, "NEAR_DUP", clusterId = "cSum"),
            photo("d", old, "CAPTION_QUEUED", salience = 0.9f),
            photo("e", old, "EXTRACTING"), photo("f", old, "PENDING"), photo("g", old, "FAILED"),
        )
        pass(settings(since = old), t0 + 400 * day)
        files.forEach { assertTrue(it.exists()) }
        assertEquals(7, graph.db.frameDao().count())
    }

    @Test fun legacyOrphanPassSkipsKeyframes() = runBlocking {
        val legacy = File(ctx.filesDir, "rokid_archive/legacy.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("legacy", now - 31 * day, legacy.absolutePath, "rokid_glasses"))
        val key = photo("kf", now - 31 * day, "EXTRACTED") // has frame_meta
        val inCl = File(ctx.filesDir, "rokid_archive/incl.jpg").apply { writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("incl", now - 31 * day, inCl.absolutePath, "rokid_glasses"))
        graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity("incl", "IN_CLUSTER", 0, null, null, null, 1), FrameStateEntity("cq", "CAPTION_QUEUED", 0, null, null, null, 1)))
        val cq = File(ctx.filesDir, "rokid_archive/cq.jpg").apply { writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("cq", now - 31 * day, cq.absolutePath, "rokid_glasses"))
        assertTrue(work() is ListenableWorker.Result.Success)
        assertFalse(legacy.exists()); assertNull(frameRow("legacy")) // the old rule still applies to legacy frames
        assertTrue(key.exists() && frameRow("kf") != null)
        assertTrue(inCl.exists() && cq.exists())
    }

    @Test fun orphanQueryExcludesInClusterCaptionQueuedAndOpenClusters() = runBlocking {
        cluster("cOpen", "CLOSED")
        val dao = graph.db.forgetDao()
        for ((id, st) in listOf("x1" to "IN_CLUSTER", "x2" to "CAPTION_QUEUED", "x3" to "CLUSTERED", "x4" to "EXTRACTED")) {
            graph.db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
            graph.db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, st, 0, null, null, null, 1)))
        }
        graph.db.frameMetaDao().upsert(FrameMetaEntity("x3", null, null, "STILL", false, null, null, null, 640, null, null, "cOpen", null, null, 1))
        assertEquals(listOf("x4"), dao.orphanFramesBefore(now, 10))
    }

    // --- Fix round 1: data-loss audit -----------------------------------------------------------------------------------
    private suspend fun fact(id: String, frameId: String, ageMs: Long, at: Long = now) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at - ageMs, false, "t"))
            .also { graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity(id, frameId, null, "FACT"))) }

    /** C1: a SHORT fact expiring at 24 h must not tombstone the photo, delete its JPEG or its vectors; the photo rule then runs. */
    @Test fun memoryExpiryNeverTouchesKeyframePhoto() = runBlocking {
        val jpg = photo("kf", now - 2 * hour, "EXTRACTED", linked = true) // has a caption (LONG, forever)
        fact("fact_kf", "kf", 25 * hour)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertFalse(exists("fact_kf")); assertTrue(exists("m_kf"))
        assertTrue(jpg.exists()); assertNotNull(frameRow("kf"))
        assertNotNull(graph.db.frameVectorDao().get("kf", "sp")); assertNotNull(graph.db.frameMetaDao().get("kf")); assertNotNull(graph.db.frameStateDao().get("kf"))
        assertTrue(graph.db.forgetDao().tombstoned(listOf("kf")).isEmpty())
        val since = MemorySettingsStore.get(ctx).settings.value.perKindSince
        pass(MemorySettingsStore.get(ctx).settings.value, since + 8 * day)
        assertNull(frameRow("kf")); assertFalse(jpg.exists()) // photoDays, through expirePhotos
        assertNotNull(graph.db.frameVectorDao().get("kf", "sp")); assertNotNull(graph.db.frameMetaDao().get("kf")); assertTrue(exists("m_kf"))
    }

    /** C1(b): the last citing memory expiring must not delete the photo or its vectors. */
    @Test fun lastCitingMemoryExpiringKeepsPhotoAndVectors() = runBlocking {
        val jpg = photo("only", now - 2 * hour, "EXTRACTED")
        fact("fact_only", "only", 25 * hour)
        work()
        assertFalse(exists("fact_only"))
        assertTrue(jpg.exists()); assertNotNull(frameRow("only")); assertNotNull(graph.db.frameVectorDao().get("only", "sp"))
        assertTrue(graph.db.forgetDao().tombstoned(listOf("only")).isEmpty())
    }

    @Test fun captionExpiryBeforePhotoExpiryKeepsPhotoAndVector() = runBlocking {
        val ts = t0 + hour
        val jpg = photo("cp", ts, "EXTRACTED", linked = true)
        pass(settings(photo = 90, caption = 30), t0 + 31 * day)
        assertFalse(exists("m_cp")) // the caption's own rule
        assertTrue(jpg.exists()); assertNotNull(frameRow("cp")); assertNotNull(graph.db.frameVectorDao().get("cp", "sp"))
        assertTrue(graph.db.forgetDao().tombstoned(listOf("cp")).isEmpty())
    }

    /** A cluster summary expiring takes its CLUSTER_REP rows with it; the representative must not become a skipped photo. */
    @Test fun summaryExpiryDoesNotMakeRepresentativesSkipped() = runBlocking {
        graph.db.frameClusterDao().upsert(FrameClusterEntity("cr", 1, 2, "gap", "[\"rep\"]", null, state = "SUMMARIZED"))
        val rep = photo("rep", t0 + hour, "CLUSTERED", clusterId = "cr")
        val member = photo("mb", t0 + hour, "CLUSTERED", clusterId = "cr")
        graph.db.memoryDao().insert(MemoryEntity("sumx", "rep", "t", "cluster_summary", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, t0 - 40 * day, false, "t"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("sumx", "rep", "cr", "CLUSTER_REP"), FrameSourceEntity("sumx", "mb", "cr", "CLUSTER_MEMBER")))
        pass(settings(summary = 30), t0 + 12 * hour)
        assertFalse(exists("sumx"))
        assertTrue(rep.exists() && frameRow("rep") != null) // a representative, not a skipped photo
        assertTrue(graph.db.forgetDao().tombstoned(listOf("rep", "mb")).isEmpty())
        pass(settings(summary = 30), t0 + 26 * hour)
        assertTrue(rep.exists())
        assertNull(frameRow("mb")); assertFalse(member.exists()) // the non-representative member is skipped
    }

    /** I1: while describe-before-delete is not shipped, an undescribed non-skipped photo is never deleted. */
    @Test fun describeGateBothWays() = runBlocking {
        val und = photo("und", t0 + hour, "EXTRACTED")
        val lnk = photo("lnk", t0 + hour, "EXTRACTED", linked = true)
        val skp = photo("skp", t0 + hour, "NEAR_DUP", salience = 0.1f)
        assertEquals(false, Retention.DESCRIBE_BEFORE_DELETE_READY)
        pass(settings(), t0 + 60 * day) // gate off (the build's constant)
        assertTrue(und.exists()); assertFalse(lnk.exists()); assertFalse(skp.exists())
        val on = pass(settings(), t0 + 60 * day, gate = true)
        assertFalse(und.exists()); assertEquals(1, on.deletedUndescribed)
    }

    /** I2: every pre-existing photo is protected for 7 days after the stamp, whatever its kind or setting. */
    @Test fun preExistingPhotosProtectedSevenDaysForEveryKind() = runBlocking {
        for (skipped in listOf(0, 1, 7)) for (photoDays in listOf(1, 3, 7)) {
            val tag = "s${skipped}p$photoDays"
            val trip = t0 - 20 * day
            val sk = photo("sk$tag", trip, "NEAR_DUP", lastError = "shed_backlog")
            val lk = photo("lk$tag", trip, "EXTRACTED", linked = true)
            val st = settings(photo = photoDays, skipped = skipped)
            pass(st, t0); pass(st, t0 + day); pass(st, t0 + 6 * day + 23 * hour)
            assertTrue("$tag skipped kept", sk.exists() && frameRow("sk$tag") != null)
            assertTrue("$tag linked kept", lk.exists() && frameRow("lk$tag") != null)
            pass(st, t0 + 7 * day + hour)
            assertNull("$tag skipped gone", frameRow("sk$tag")); assertNull("$tag linked gone", frameRow("lk$tag"))
        }
    }

    /** I3: a stale / restored stamp, a clock moved backwards or a long gap since the last run never brings the bulk delete back. */
    @Test fun staleStampDoesNotBulkDelete() = runBlocking {
        val store = MemorySettingsStore.get(ctx)
        val trip = t0 - 20 * day
        repeat(50) { photo("lk$it", trip, "EXTRACTED", linked = true); photo("sk$it", trip, "NEAR_DUP", salience = 0.1f) }
        store.update { it.copy(perKindSince = t0 - 30 * day, perKindLastRun = t0 - 30 * day) } // restored from an old build switch
        assertTrue(work() is ListenableWorker.Result.Success)
        assertEquals(100, graph.db.frameDao().count())
        assertTrue(store.settings.value.perKindSince >= t0)
    }

    /** M2 / ruling: a CAPTION_QUEUED frame still queued after 14 days becomes a skipped photo (never before the protection ends). */
    @Test fun captionQueuedBecomesSkippedAfterFourteenDays() = runBlocking {
        val q = photo("cq1", t0 - 20 * day, "CAPTION_QUEUED", salience = 0.9f)
        pass(settings(), t0 + 13 * day)
        assertEquals("CAPTION_QUEUED", graph.db.frameStateDao().get("cq1")!!.state); assertTrue(q.exists())
        pass(settings(), t0 + 15 * day)
        val st = graph.db.frameStateDao().get("cq1")!!
        assertEquals("NEAR_DUP", st.state); assertEquals("caption_expired", st.lastError); assertTrue(q.exists())
        pass(settings(), t0 + 15 * day + 25 * hour)
        assertNull(frameRow("cq1")); assertFalse(q.exists())
    }

    // --- Fix round 2: the sweep must never own keyframes -------------------------------------------------------------
    private suspend fun tombstone(id: String) = graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity(id, 1))

    /** N1: a keyframe an earlier build tombstoned (row kept) whose last MEDIUM fact expires keeps its JPEG, vectors and meta through the sweep and at start-up. */
    @Test fun tombstonedKeyframeSurvivesSweepWhenItsLastFactExpires() = runBlocking {
        val jpg = photo("tk", now - 2 * hour, "EXTRACTED")
        tombstone("tk")
        graph.db.memoryDao().insert(MemoryEntity("fact_tk", "tk", "t", "note", "", MemoryTier.MEDIUM, 0.8, ReviewState.OK, false, now - 8 * day, false, "t"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("fact_tk", "tk", null, "FACT")))
        assertTrue(work() is ListenableWorker.Result.Success) // expires the fact, then sweeps
        assertFalse(exists("fact_tk"))
        FrameSweep.sweep(ctx, now + 30 * day)
        ForgetStartup.run(ctx) {}
        assertTrue(jpg.exists()); assertNotNull(frameRow("tk"))
        assertNotNull(graph.db.frameVectorDao().get("tk", "sp")); assertNotNull(graph.db.frameMetaDao().get("tk")); assertNotNull(graph.db.frameStateDao().get("tk"))
    }

    /** N1: an expiring cluster summary does not take its cluster's tombstoned frames with it. */
    @Test fun expiringClusterSummaryKeepsItsTombstonedFrames() = runBlocking {
        graph.db.frameClusterDao().upsert(FrameClusterEntity("cx", 1, 2, "gap", "[\"r1\"]", null, state = "SUMMARIZED"))
        val r1 = photo("r1", t0 + hour, "CLUSTERED", clusterId = "cx"); val m1 = photo("m1", t0 + hour, "CLUSTERED", clusterId = "cx", salience = 0.9f)
        listOf("r1", "m1").forEach { tombstone(it) }
        graph.db.memoryDao().insert(MemoryEntity("sumy", "r1", "t", "cluster_summary", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, t0 - 40 * day, false, "t"))
        graph.db.frameSourceDao().insertAll(listOf(FrameSourceEntity("sumy", "r1", "cx", "CLUSTER_REP"), FrameSourceEntity("sumy", "m1", "cx", "CLUSTER_MEMBER")))
        pass(settings(summary = 30), t0 + hour)
        assertFalse(exists("sumy"))
        FrameSweep.sweep(ctx, t0 + 40 * day); ForgetStartup.run(ctx) {}
        assertTrue(r1.exists() && m1.exists()); assertNotNull(frameRow("r1")); assertNotNull(frameRow("m1"))
        assertNotNull(graph.db.frameVectorDao().get("r1", "sp"))
    }

    /** A legacy frame (no frame_meta) that is tombstoned and uncited is still swept. */
    @Test fun legacyTombstonedFrameIsStillSwept() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/lg.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("lg", 1, jpg.absolutePath, "rokid_glasses"))
        tombstone("lg")
        FrameSweep.sweep(ctx)
        assertFalse(jpg.exists())
    }

    /** Minor: a CAPTION_QUEUED frame in an unsummarised cluster is not converted. */
    @Test fun captionQueuedInUnsettledClusterIsNotConverted() = runBlocking {
        cluster("cu", "CLOSED")
        photo("cq2", t0 - 20 * day, "CAPTION_QUEUED", salience = 0.9f, clusterId = "cu")
        pass(settings(), t0 + 30 * day)
        assertEquals("CAPTION_QUEUED", graph.db.frameStateDao().get("cq2")!!.state)
        graph.db.frameClusterDao().upsert(FrameClusterEntity("cu", 1, 2, "gap", "[]", null, state = "SUMMARIZED"))
        pass(settings(), t0 + 31 * day)
        assertEquals("NEAR_DUP", graph.db.frameStateDao().get("cq2")!!.state)
    }
}
