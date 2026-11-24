package com.rokid.context.memory.forget

import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasLiveTraceEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasScoreEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.moment.MomentId

/**
 * The fixed world every golden delete-set test and the planner shadow test start from (Forget lifecycle M0, task T1). Names tell the role:
 * photos `fA` (forgotten), `fB` (one second later: inside fA's time radius, a batch mate), `fFar` (50 minutes later: outside every radius);
 * memories `mA` (caption of fA), `mD` (derived from mA), `mBatch` (fB's caption that CARRIES fA), `mMulti` (cites fA and fFar), `mPlace` (a Place Guide memory of fA: type "place" plus a `frame_source` FACT row), `mFar`;
 * asks and nudges citing them, a PENDING and an EXECUTED proposal, marks near / linked / far, a live trace, run results of two batches, Moments and Scenes of the three photos, a derived field and audit of Moment A.
 */
class GoldenWorld(val db: ContextDatabase, now: Long = 1_000) {
    val fx = MomentForgetFixture(db, now)
    val service get() = fx.service
    val base = MomentForgetFixture.MS
    val sec = MomentForgetFixture.SEC
    val tA = base + 100_000; val tB = base + 101_000; val tFar = base + 3_000_000
    val momA = MomentId.format(sec + 100); val momB = MomentId.format(sec + 101); val momFar = MomentId.format(sec + 3000)
    private val epoch = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, epoch, { 0 }, { epoch })
    val proposals = ProposalRepository(db.proposalDao(), db.actionDao(), db)

    suspend fun seed() {
        fx.frame("fA", tA, wire = "wA", capturedAt = tA); fx.frame("fB", tB, wire = "wB", capturedAt = tB); fx.frame("fFar", tFar, wire = "wF", capturedAt = tFar)
        fx.memory("mA", "fA"); fx.memory("mD", null, derived = true); fx.derivedFrom("mD", "mA")
        fx.memory("mBatch", "fB"); fx.memory("mMulti", "fA"); fx.memory("mFar", "fFar")
        db.memoryDao().insert(MemoryEntity("mPlace", "fA", "Place name", "place", "", MemoryTier.LONG, 0.8, ReviewState.OK, false, 1, false, "Place name"))
        db.frameSourceDao().insertAll(listOf(
            FrameSourceEntity("mBatch", "fA", null, "CARRIED"),
            FrameSourceEntity("mMulti", "fA", null, "FACT"), FrameSourceEntity("mMulti", "fFar", null, "FACT"),
            FrameSourceEntity("mPlace", "fA", null, "FACT"),
        ))
        val ask = db.askDao()
        ask.upsert(AskEntity("aCite", 1, "q1", "a1", "[\"mA\"]", null, "test", false))
        ask.upsert(AskEntity("aDep", 1, "q2", "a2", "[\"mFar\",{\"dependsOn\":[\"mD\"]}]", null, "test", false))
        ask.upsert(AskEntity("aFrame", 1, "q3", "a3", "[\"frame:fA\"]", null, "test", false))
        ask.upsert(AskEntity("aKeep", 1, "q4", "a4", "[\"mFar\"]", null, "test", false))
        val nudge = db.nudgeDao()
        nudge.insertIgnore(NudgeEntity("nCite", 1, "n1", "why", "[\"mA\"]", 1, "h1", "PHONE", false))
        nudge.insertIgnore(NudgeEntity("nKeep", 2, "n2", "why", "[\"mFar\"]", 1, "h2", "PHONE", false))
        proposals.create(ProposalEntity("pAsk", ProposalOrigin.ASK.name, "aCite", "REMINDER", "{\"title\":\"t\"}", ProposalState.PENDING.name, 99_999, null, null, 1), 1)
        proposals.create(ProposalEntity("pNudge", ProposalOrigin.NUDGE.name, "nCite", "REMINDER", "{\"title\":\"t\"}", ProposalState.EXECUTED.name, 99_999, null, null, 1), 1)
        val s3 = db.canvasSlice3Dao()
        s3.insertLabelFenced(CanvasLabelEntity("mkNear", "MOMENT", "tap", "MOMENT", tA + 500, createdAt = 1), fence)
        s3.insertLabelFenced(CanvasLabelEntity("mkNear:i", "INTENT", "tap", "walk", tA + 500, createdAt = 1), fence)
        s3.insertLabelFenced(CanvasLabelEntity("mkLinked", "MOMENT", "tap", "MOMENT", base + 1_500_000, frameId = "fA", createdAt = 1), fence)
        s3.insertLabelFenced(CanvasLabelEntity("mkFar", "MOMENT", "tap", "MOMENT", tFar + 500, createdAt = 1), fence)
        s3.acceptTraceFenced(CanvasTraceEntity("trNear", "IMU", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = tA - 3_000, endedAtMs = tA + 3_000, createdAt = 1), fence)
        s3.acceptTraceFenced(CanvasTraceEntity("trFar", "IMU", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = tFar - 3_000, endedAtMs = tFar + 3_000, createdAt = 1), fence)
        val canvas = db.canvasDao()
        fun rows(frame: String, batch: String): List<suspend () -> Unit> = listOf<suspend () -> Unit>(
            { canvas.upsertItem(CanvasRunItemEntity("r", frame, "n", "OK", null, "{}", null, null, null, null, null, null, batch)) },
            { canvas.upsertScore(CanvasScoreEntity("r", frame, "n", 1, "", 1)) },
            { canvas.upsertTrace(listOf(CanvasLiveTraceEntity(7, frame, "n", "LIVE", "OK", null, "{}", null, batch, 1))) },
        )
        // fA and fB share the provider call b1 (batch lineage); fFar was alone in b2
        listOf("fA" to "b1", "fB" to "b1", "fFar" to "b2").forEach { (f, b) -> rows(f, b).forEach { it() } }
        val md = db.momentDao()
        fx.project(momA, "FRAME" to "fA", "MEMORY" to "mA", "MEMORY" to "mPlace", "MARK" to "mkNear")
        fx.project(momB, "FRAME" to "fB", "MEMORY" to "mBatch")
        fx.project(momFar, "FRAME" to "fFar", "MEMORY" to "mFar")
        db.objectDao().writeDerivedTx(ObjectDerivedEntity("MOMENT", momA, "SUMMARY", "derived summary", null, 1, null, tA), "build")
        val sd = db.sceneDao()
        fun scene(id: String, from: Long, to: Long) = SceneEntity(id, 1, 0L, from, to, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L)
        sd.replace(scene("sNear", tA, tB + 999), listOf(momA, momB).map { ScenePartEntity("sNear", ScenePartKind.MOMENT, it, "r", MomentId.startMs(it)!!) })
        sd.replace(scene("sMixed", tB, tFar + 999), listOf(momB, momFar).map { ScenePartEntity("sMixed", ScenePartKind.MOMENT, it, "r", MomentId.startMs(it)!!) })
        check(md.momentIdsBetween(momA, momFar).size == 3)
    }

    /** The second ids of the two lone marks of [seedLocations] (each a second of its own, outside every radius). */
    val momLone = MomentId.ofMs(base + 2_000_000); val momLone2 = MomentId.ofMs(base + 2_500_000)

    /**
     * v18 WP2, called AFTER [seed] and only by the tests that pin `moment_location` (every other golden keeps its literal). One row per Moment id, as the recorder writes them: [momA] FRAME fA at tA, [momB] FRAME fB at tB,
     * [momFar] FRAME fFar at tFar, [momLone] MARK mkLone and [momLone2] MARK mkLone2 (two marks with no photo, each in a second of its own). The delta names the rows by their Moment id.
     */
    suspend fun seedLocations() {
        val e = DeletionEpoch("i", 1, 0); val f = DaoFence(0, e, { 0 }, { e })
        db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("mkLone", "MOMENT", "tap", "MOMENT", base + 2_000_000, createdAt = 1), f)
        db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("mkLone2", "MOMENT", "tap", "MOMENT", base + 2_500_000, createdAt = 1), f)
        fun row(id: String, at: Long, kind: String, anchor: String) = MomentLocationEntity(id, at, kind, anchor, 12.5, 77.5, 20.0, 1_000, "ZONE_STREAM", null, null, 1)
        listOf(row(momA, tA, "FRAME", "fA"), row(momB, tB, "FRAME", "fB"), row(momFar, tFar, "FRAME", "fFar"), row(momLone, base + 2_000_000, "MARK", "mkLone"), row(momLone2, base + 2_500_000, "MARK", "mkLone2"))
            .forEach { db.momentLocationDao().insertIfAbsent(it) }
    }

    /**
     * v18 WP3, called AFTER [seed] and only by the tests that pin `place_card_log`. One row per card: h1 of fA at tA, h2 of fB at tB, h3 of fFar at tFar, h4 with no photo at tFar + 1 h (outside every span the goldens use).
     * (The user's `fA` Forget deletes h1; a range over the first two seconds deletes h1 and h2 by time.)
     */
    suspend fun seedHistory() {
        fun row(id: String, at: Long, frame: String?) = PlaceCardLogEntity(id, at, "wd:Q1", "Site", "PHOTO", null, frame, null, "c$id", "SENT", 0)
        listOf(row("h1", tA, "fA"), row("h2", tB, "fB"), row("h3", tFar, "fFar"), row("h4", tFar + 3_600_000, null)).forEach { db.placeCardLogDao().put(it) }
    }

    /** The delta of [op] on a freshly seeded world. */
    suspend fun deltaOf(op: suspend () -> Unit): DbSnapshot.Delta {
        val before = DbSnapshot.of(db); op(); return before.deltaTo(DbSnapshot.of(db))
    }
}
