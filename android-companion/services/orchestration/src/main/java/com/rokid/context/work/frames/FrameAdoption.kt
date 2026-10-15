package com.rokid.context.work.frames

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.orchestration.MemoryWipe
import java.io.File

/**
 * One-shot, after the Room v1 -> v2 migration (prefs flag [FLAG]): gives every frame that existed before v2 its
 * `frame_state` row so the keyframe backlog sees it (spec P2, Review Focus 2). All inserts are IGNORE, so a frame that
 * already has a row, and a re-run, change nothing.
 *
 * - archived frames still awaiting extraction (no memory, no `frame_source`, no tombstone, JPEG on disk) -> PENDING, including
 *   those whose 24 h queue job expired;
 * - frames that already produced a memory -> EXTRACTED (never re-queued, never duplicated);
 * - tombstoned frames -> no row.
 *
 * Inbox files have no database id yet: the drain ([RokidFrameInbox]) writes their PENDING row in the same transaction as the
 * frame row, so they are adopted exactly once there, whenever they are drained.
 */
class FrameAdoption(
    private val db: ContextDatabase,
    private val prefs: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
    /** The Delete all generation ([MemoryWipe.generation]); a test moves it to simulate a wipe racing the run. */
    private val generation: () -> Long = { MemoryWipe.generation.get() },
) {
    data class Report(val alreadyDone: Boolean, val pending: Int = 0, val extracted: Int = 0, val missingFile: Int = 0)

    suspend fun run(): Report {
        if (prefs.getBoolean(FLAG, false)) return Report(alreadyDone = true)
        val dao = db.frameAdoptionDao()
        val states = db.frameStateDao()
        val t = now()
        val g0 = generation()
        var wiped = false
        // Review B M-5: a Delete all bumps the generation BEFORE it deletes rows, so a page written under a changed generation,
        // checked inside the same transaction as the insert, can never leave a frame_state row for a wiped frame.
        suspend fun write(rows: List<FrameStateEntity>): Boolean {
            if (wiped) return false
            val ok = db.withTransaction { if (generation() != g0) false else { states.insertIgnore(rows); true } }
            if (!ok) wiped = true
            return ok
        }
        var extracted = 0
        var after = ""
        while (true) {
            val ids = dao.extracted(after, PAGE)
            if (ids.isEmpty()) break
            if (!write(ids.map { row(it, "EXTRACTED", t) })) break
            extracted += ids.size
            after = ids.last()
        }
        var pending = 0
        var missing = 0
        after = ""
        while (true) {
            val page = dao.awaiting(after, PAGE)
            if (page.isEmpty()) break
            val (there, gone) = page.partition { fileExists(it.path) }
            if (!write(there.map { row(it.id, "PENDING", t) })) break
            pending += there.size
            missing += gone.size
            after = page.last().id
        }
        if (wiped) Log.i(TAG, "Delete all ran during adoption: stopped")
        prefs.edit().putBoolean(FLAG, true).commit() // after a wipe there is nothing left to adopt
        Log.i(TAG, "adopted pending=$pending extracted=$extracted missingFile=$missing")
        return Report(alreadyDone = false, pending = pending, extracted = extracted, missingFile = missing)
    }

    private fun row(id: String, state: String, t: Long) = FrameStateEntity(id, state, 0, null, null, null, t)

    companion object {
        const val FLAG = "frames_adopted_v2"
        private const val PREFS = "frame_adoption"
        private const val PAGE = 500
        private const val TAG = "FrameAdoption"

        suspend fun run(context: Context): Report = FrameAdoption(
            MemoryGraph.get(context).db,
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        ).run()
    }
}
