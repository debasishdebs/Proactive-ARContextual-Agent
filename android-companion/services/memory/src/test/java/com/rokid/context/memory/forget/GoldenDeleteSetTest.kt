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
     * [on]: M5 (rulings 2026-11-17), the ON-only expectation of a path whose ask / nudge fates changed: an answer is hit by ALL its stored dependencies (A1, conservative per proxy ruling F4: "Answered from a forgotten fact", the question stays) and a nudge
     * reached by any dependency is deleted (N1). Everything else equals [expected]; the OFF expectation is the v1 set and never changes.
     */
    private class V2Fates(
        /** The asks that change with the flag ON (null: the same as OFF). The seeded asks have no source states (written before v17): their stored ids (cited and `dependsOn`) count, so `aDep` is hit exactly as with the flag OFF (ruling F4); null means the same ask set as OFF. */
        val askChanged: String?,
        /** The nudge(s) reached by a dependency: ERASED (a deleted row), no longer "changed" (redacted in place). */
        val nudgeErased: String,
    ) {
        fun apply(off: String): String {
            val lines = off.trimIndent().trim().lines().toMutableList()
            if (askChanged != null) lines[lines.indexOfFirst { it.startsWith("changed ask:") }] = "changed ask: $askChanged"
            lines.removeAll { it.startsWith("changed nudge:") }
            val deleted = (lines.filter { it.startsWith("deleted ") } + "deleted nudge: $nudgeErased").sortedBy { it.substringAfter("deleted ").substringBefore(":") }
            return (deleted + lines.filterNot { it.startsWith("deleted ") }).joinToString("\n")
        }
    }

    /** [onFull]: the COMPLETE ON expectation of a path the Stage 2 rulings (F1, F3, F5, F6, 2026-11-17) change beyond the ask and nudge fates; the OFF expectation never changes. */
    private fun golden(expected: String, on: V2Fates? = null, onFull: String? = null, locations: Boolean = false, history: Boolean = false, centroids: Boolean = false, attribution: Boolean = false, op: suspend () -> Unit) = runBlocking {
        for (v2 in listOf(false, true)) {
            ForgetV2.testOverride = v2
            val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                w = GoldenWorld(db)
                w.seed(); if (locations) w.seedLocations(); if (history) w.seedHistory(); if (centroids) w.seedCentroids(); if (attribution) w.seedAttribution()
                val want = (if (v2 && onFull != null) onFull else if (v2 && on != null) on.apply(expected) else expected).trimIndent().trim(); val got = w.deltaOf(op).render()
                if (want != got) throw AssertionError("forgetV2=$v2 delete set differs.\n--- expected\n$want\n--- actual\n$got") // the whole set, not JUnit's abbreviated diff
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
        """, on = V2Fates(null, "nCite")) { w.service.forgetFact("mA") }
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
        """, on = V2Fates(null, "nCite"), onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 6
            added forget_tombstone: fA
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
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
        """, on = V2Fates(null, "nKeep"), onFull = """
            deleted canvas_label: mkFar
            deleted canvas_live_trace: 7|fA|n, 7|fB|n, 7|fFar|n
            deleted canvas_run_item: r|fA|n, r|fB|n, r|fFar|n
            deleted canvas_score: r|fA|n, r|fB|n, r|fFar|n
            deleted frame: fFar
            deleted frame_meta: fFar
            deleted frame_source: mMulti|fA, mMulti|fFar
            deleted memory: mFar, mMulti
            deleted moment: 68f6c5f8
            deleted moment_part: 68f6c5f8|FRAME|fFar, 68f6c5f8|MEMORY|mFar
            deleted nudge: nKeep
            deleted scene_part: sMixed|MOMENT|68f6c5f8
            changed ask: aDep, aKeep
            changed canvas_meta: labelRev
            changed canvas_trace: trFar
            changed scene: sMixed
            added canvas_meta: forgetRanges, frame_forget_rev
            added cleanup_outbox: 1, 2, 3, 4, 6
            added forget_tombstone: fFar
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
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
        """, on = V2Fates(null, "nCite"), onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 7, 8
            added forget_tombstone: fA, fB
            added moment_tombstone: 68f6baa4, 68f6baa5
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
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
        """, on = V2Fates(null, "nCite"), onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA
            added moment_tombstone: 68f6baa4
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
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
        """, on = V2Fates(null, "nCite"), onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA
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
        """, on = V2Fates(null, "nCite"), onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5, 6, 7, 8
            added forget_tombstone: fA, fB
            added moment_tombstone: 68f6baa4, 68f6baa5
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """) { w.fx.forget.forgetSpans(listOf(w.tA..w.tB + 999), listOf("sNear")) }
    /** Retention v3: photo expiry is storage only: the frame row and the photo's own experiment canvas outputs go; the expiry marker is written (rx:f:, and rx:t: by capture time); no Moment, Scene, mark or radius cascade, no fence. There is no memory expiry: a fact is never deleted by retention. */
    /** v18 WP2: only the rows of [GoldenWorld.seedLocations] (and the marks it adds) are new here; a user Forget of the moment deletes the saved location of its photo and of every photo it locks, nothing else. */
    @Test fun momentLocationRows() = golden("""
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
            deleted moment_location: 68f6baa4, 68f6baa5, 68f6c5f8
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
        """, onFull = """
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
            deleted moment_location: 68f6baa4, 68f6baa5
            deleted moment_part: 68f6baa4|FRAME|fA, 68f6baa4|MARK|mkNear, 68f6baa4|MEMORY|mA, 68f6baa4|MEMORY|mPlace, 68f6baa5|FRAME|fB, 68f6baa5|MEMORY|mBatch
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA
            added moment_tombstone: 68f6baa4
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """, locations = true) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun sceneCentroidRows() = golden("""
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
            deleted scene_centroid: sMixed|e1, sNear|e1, sNear|e2
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
        """, onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted scene: sNear
            deleted scene_centroid: sMixed|e1, sNear|e1, sNear|e2
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA
            added moment_tombstone: 68f6baa4
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """, centroids = true) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    /** v18 WP11a: the model attribution of a deleted memory, derived field and redacted answer goes with it (here: Forget of a fact and of a Moment). */
    @Test fun modelAttributionRows() = golden("""
            deleted canvas_label: mkLinked, mkNear, mkNear:i
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted memory: mA
            deleted memory_source: mD|mA
            deleted model_attribution: ANSWER|aCite, DERIVED|MOMENT:68f6baa4:SUMMARY, MEMORY|mA
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
        """, on = V2Fates(null, "nCite"), attribution = true) { w.service.forgetFact("mA") }

    /** Photo expiry keeps the caption memory, so it keeps its attribution row (no `model_attribution` line). */
    @Test fun modelAttributionRowsKeptByExpiry() = golden("""
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            added canvas_meta: rx:f:fA, rx:t:1761000100000:fA
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
        """, attribution = true) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }

    /** The same centroids are KEPT by photo expiry (the golden of `retentionExpirePhoto` with the rows seeded: no `scene_centroid` line). */
    @Test fun sceneCentroidRowsKeptByExpiry() = golden("""
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            added canvas_meta: rx:f:fA, rx:t:1761000100000:fA
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
        """, centroids = true) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    /** The same rows are KEPT by photo expiry (the golden of `retentionExpirePhoto` with the rows seeded: no `moment_location` line). */
    @Test fun momentLocationRowsKeptByExpiry() = golden("""
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            added canvas_meta: rx:f:fA, rx:t:1761000100000:fA
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
        """, locations = true) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    /** v18 WP3: a user Forget of the moment deletes the history rows of its photos (fA) and of the span; rows of other photos and times stay. */
    @Test fun placeHistoryRows() = golden("""
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
            deleted place_card_log: h1, h2, h3
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
        """, onFull = """
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
            deleted nudge: nCite
            deleted object_audit: MOMENT|68f6baa4|1
            deleted object_derived: MOMENT|68f6baa4|SUMMARY
            deleted place_card_log: h1, h2
            deleted scene: sNear
            deleted scene_part: sMixed|MOMENT|68f6baa5, sNear|MOMENT|68f6baa4, sNear|MOMENT|68f6baa5
            changed action_log: 1, 2
            changed ask: aCite, aDep, aFrame
            changed canvas_meta: labelRev
            changed canvas_trace: trNear
            changed memory: mD
            changed proposal: pAsk, pNudge
            changed scene: sMixed
            added action_log: 3
            added canvas_meta: forgetRanges, frame_forget_rev, momentForgetRanges, scene_seq_floor
            added cleanup_outbox: 1, 2, 3, 4, 5
            added forget_tombstone: fA
            added moment_tombstone: 68f6baa4
            added object_audit: SCENE|sMixed|1, SCENE|sMixed|2, SCENE|sMixed|3
        """, history = true) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    /** The same rows are KEPT by photo expiry apart from the unlink (`photoGone = 1`, the frameId stays). */
    @Test fun placeHistoryRowsUnlinkedByExpiry() = golden("""
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            changed place_card_log: h1
            added canvas_meta: rx:f:fA, rx:t:1761000100000:fA
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
        """, history = true) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
    @Test fun retentionExpirePhoto() = golden("""
            deleted canvas_live_trace: 7|fA|n, 7|fB|n
            deleted canvas_run_item: r|fA|n, r|fB|n
            deleted canvas_score: r|fA|n, r|fB|n
            deleted frame: fA
            added canvas_meta: rx:f:fA, rx:t:1761000100000:fA
            added cleanup_outbox: 1, 2
            added forget_tombstone: fA
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
