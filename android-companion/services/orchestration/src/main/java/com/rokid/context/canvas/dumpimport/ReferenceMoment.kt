package com.rokid.context.canvas.dumpimport

import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.moment.MomentId
import java.util.UUID

/**
 * Developer "Add place manually", the reference photo: ONE Moment made of one photo (and, when there is a description, one memory with the attribution `imported`, no model call) through the SAME writers as the Developer
 * import ([ImportFrameWriter] = the frame ingest, [ImportMemoryWriter] = the memory writer, the Moment builder), so Forget, Delete all, retention and the quota treat it like any photo. Differences from a captured Moment, all
 * on purpose: the time is NOW (the photo was not captured here), the frame id is a new uuid (the Moment id is the second, as for every Moment), and there is NO `moment_location` row and no coordinate anywhere.
 * Nothing is half-written: when any step fails, what was written is removed again through [discard] (the Forget path) and null comes back. A second that is already used by another Moment, or fenced, moves on to the next
 * second (at most [TRIES]). Nothing here logs or returns text, ids or coordinates to a logger.
 */
class ReferenceMoment(
    private val db: ContextDatabase,
    private val frames: ImportFrameWriter,
    private val memories: ImportMemoryWriter,
    private val build: suspend (List<String>) -> Unit,
    private val generation: () -> Long,
    /** Removes a photo again by its frame id (the Forget path); true = gone. */
    private val discard: suspend (frameId: String) -> Boolean,
    private val wall: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** The new Moment's id and its photo's frame id. */
    class Made(val momentId: String, val frameId: String)

    suspend fun create(photo: ByteArray, description: String): Made? {
        val gen0 = generation()
        var sec = Math.floorDiv(wall(), 1000L)
        for (attempt in 0 until TRIES) {
            val id = try { MomentId.format(sec) } catch (e: IllegalArgumentException) { return null }
            if (free(id, sec)) return write(id, sec, photo, description, gen0)
            sec += 1
        }
        return null
    }

    private suspend fun free(id: String, sec: Long): Boolean =
        db.momentDao().tombstonedAmong(listOf(id)).isEmpty() && !db.canvasSlice3Dao().momentFenced(sec * 1000, sec * 1000 + 999) &&
            db.momentDao().moment(id) == null && db.momentDao().photoMomentsOf(listOf(id)).isEmpty() && db.momentDao().labelSeconds(sec * 1000, sec * 1000 + 999).isEmpty()

    private suspend fun write(id: String, sec: Long, photo: ByteArray, description: String, gen0: Long): Made? {
        val fid = "ref-" + newId().replace("-", "").take(32)
        val at = maxOf(wall(), sec * 1000).coerceAtMost(sec * 1000 + 999)
        val frame = DumpImportParser.Frame(fid, at, SOURCE, "UNKNOWN", false, null, null, null, null, null, null, photo)
        var stored = false
        try {
            if (!frames.store(frame, gen0)) return null
            stored = true
            val text = description.trim()
            if (text.isNotEmpty()) {
                val m = DumpImportParser.Memory("ref-m-" + newId().replace("-", "").take(32), fid, text, "caption", emptyList(), MemoryTier.SHORT, 1.0, ReviewState.OK, at, false)
                if (generation() != gen0 || !memories.store(m)) throw Undo()
            }
            if (generation() != gen0) throw Undo()
            build(listOf(id))
            if (db.momentDao().moment(id) == null || db.momentDao().photoMomentsOf(listOf(id)).isEmpty()) throw Undo()
            return Made(id, fid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (stored) try { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { discard(fid) } } catch (_: Exception) { }
            throw e
        } catch (e: Exception) {
            if (stored) try { discard(fid) } catch (_: Exception) { }
            return null
        }
    }

    private class Undo : Exception(null, null, false, false)

    companion object {
        const val TRIES = 3
        /** The frame source of a reference photo: the live location recorder never takes a frame of this source as a candidate. */
        const val SOURCE = "rokid_reference"
    }
}
