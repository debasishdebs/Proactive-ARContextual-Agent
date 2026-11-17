package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle M0 (task T1): the EXACT set every current Forget path deletes, redacts, invalidates or fences, pinned row by row on the fixed [GoldenWorld]. Each test seeds a fresh world, runs ONE
 * path and compares the rendered [DbSnapshot.Delta] (every table, keys only) to the literal below. Green on today's code; the lifecycle work (M4 and later) must keep these sets unless the user rules a change,
 * and the planner shadow test (M2) asserts the planner's set equals them. If a path's set changes, this fails with the new set printed: decide, then update the golden.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GoldenDeleteSetTest {
    private lateinit var w: GoldenWorld
    @After fun close() { ForgetV2.testOverride = null }

    /**
     * M4: every path runs the SAME golden set twice on a fresh world, with `forgetV2` OFF (the legacy code) and ON (the [com.rokid.context.memory.forget.lifecycle.ForgetExecutor]): the executor must delete exactly
     * what v1 deletes. The message names the mode, so a failure says which path diverged.
     */
    private fun golden(expected: String, op: suspend () -> Unit) = runBlocking {
        for (v2 in listOf(false, true)) {
            ForgetV2.testOverride = v2
            val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                w = GoldenWorld(db)
                w.seed()
                assertEquals("forgetV2=$v2", expected.trimIndent().trim(), w.deltaOf(op).render())
            } finally { db.close() }
        }
    }

    /** The mark undo of `MarkRecorder`: the executor with the flag ON, the DAO calls it always made with the flag OFF. */
    private suspend fun undoMark(id: String) { if (ForgetV2.on) com.rokid.context.memory.forget.lifecycle.ForgetExecutor.deleteMarks(w.db, listOf(id, "$id:i")) else { w.db.canvasSlice3Dao().deleteLabel(id); w.db.canvasSlice3Dao().deleteLabel("$id:i") } }

    @Test fun forgetFact() = golden("""
            deleted canvas_label: mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted memory: mA
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.service.forgetFact("mA") }
    @Test fun forgetPhoto() = golden("""
            deleted canvas_label: mkFar, mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fA
            deleted frame_meta: fA
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5, 68f6c5f8
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch, 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sMixed, sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sMixed|MOMENT|68f6c5f8, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trFar, trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 8
            added forget_tombstone: fA, fFar
        """) { w.service.forgetMoment("fA") }
    @Test fun forgetFarPhotoCitedByAMultiPhotoFact() = golden("""
            deleted canvas_label: mkFar, mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fFar
            deleted frame_meta: fFar
            deleted frame_source: mMulti|fA, mMulti|fFar
            deleted memory: mFar, mMulti
            deleted moment: 68f6baa4, 68f6baa5, 68f6c5f8
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch, 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sMixed, sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sMixed|MOMENT|68f6c5f8, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed ask: aDep, aKeep
            changed canvas_meta: labelRev
            changed canvas_trace: trFar, trNear
            changed nudge: nKeep
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 8
            added forget_tombstone: fA, fFar
        """) { w.service.forgetMoment("fFar") }
    @Test fun forgetBatchPhoto() = golden("""
            deleted canvas_label: mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fB
            deleted frame_meta: fB
            deleted frame_source: mBatch|fA
            deleted memory: mBatch
            deleted moment: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed scene: sMixed
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 6
            added forget_tombstone: fB
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.service.forgetMoment("fB") }
    @Test fun markUndo() = golden("""
            deleted canvas_label: mkNear, mkNear:i
            changed canvas_meta: labelRev
            changed object_derived: MOMENT|68f6baa4|SUMMARY
            added object_audit: MOMENT|68f6baa4|2, MOMENT|68f6baa4|3, MOMENT|68f6baa4|4, SCENE|sNear|1
        """) { undoMark("mkNear") }
    @Test fun rangeForget() = golden("""
            deleted canvas_label: mkFar, mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fA, fB
            deleted frame_meta: fA, fB
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5, 68f6c5f8
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch, 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sMixed, sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sMixed|MOMENT|68f6c5f8, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trFar, trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 7, 8, 9
            added forget_tombstone: fA, fB, fFar
            added moment_tombstone: 68f6baa4, 68f6baa5
        """) { w.fx.forget.forgetRange(w.tA, w.tB) }
    @Test fun momentForgetById() = golden("""
            deleted canvas_label: mkFar, mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fA
            deleted frame_meta: fA
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5, 68f6c5f8
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch, 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sMixed, sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sMixed|MOMENT|68f6c5f8, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trFar, trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6
            added forget_tombstone: fA, fFar
            added moment_tombstone: 68f6baa4
        """) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun momentScopeSpans() = golden("""
            deleted canvas_label: mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fA
            deleted frame_meta: fA
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA, fFar
            added moment_tombstone: 68f6baa4
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.fx.forget.forgetSpans(listOf(w.tA..w.tA + 999)) }
    @Test fun sceneScopeSpans() = golden("""
            deleted canvas_label: mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fA, fB
            deleted frame_meta: fA, fB
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed nudge: nCite
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 7, 8
            added forget_tombstone: fA, fB, fFar
            added moment_tombstone: 68f6baa4, 68f6baa5
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.fx.forget.forgetSpans(listOf(w.tA..w.tB + 999), listOf("sNear")) }
    @Test fun retentionExpireFact() = golden("""
            deleted memory: mA
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene_part: sNear|MOMENT|68f6baa4
            changed action_log: 1
            changed ask: aCite
            changed nudge: nCite
            changed proposal: pAsk
            changed scene: sNear
            added action_log: 3
            added object_audit: SCENE|sNear|1, SCENE|sNear|2, SCENE|sNear|3
        """) { w.service.expire(listOf("mA")) }
    @Test fun retentionExpirePhoto() = golden("""
            deleted canvas_label: mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            deleted moment: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed scene: sMixed
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    @Test fun deleteAll() = golden("""
            deleted ask: aCite, aDep, aFrame, aKeep
            deleted canvas_label: mkFar, mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_trace: trFar, trNear
            deleted frame: fA, fB, fFar
            deleted frame_meta: fA, fB, fFar
            deleted frame_source: mBatch|fA, mMulti|fA, mMulti|fFar, mPlace|fA
            deleted memory: mA, mBatch, mD, mFar, mMulti, mPlace
            deleted memory_source: mD|mA
            deleted moment: 68f6baa4, 68f6baa5, 68f6c5f8
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch, 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted nudge: nCite, nKeep
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted proposal: pAsk
            deleted scene: sMixed, sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sMixed|MOMENT|68f6c5f8, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed canvas_meta: labelRev
            changed proposal: pNudge
            added canvas_helper_cleanup: 1
            added canvas_meta: frame_forget_rev
            added cleanup_outbox: 1
        """) { w.db.wipeDao().wipeMemory(1_000) }
}
