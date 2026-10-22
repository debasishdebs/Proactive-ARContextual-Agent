package com.rokid.context.canvas.marks

import com.rokid.context.core.model.MarkMessage
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.DaoFence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** When a `pc_mark` arrived and what phone time it maps to: [mappedAtMs] is the occurrence time on the phone clock ([mapped] false = the receipt time, no hello offset yet). */
data class MarkArrival(val mappedAtMs: Long, val mapped: Boolean, val receivedAtMs: Long)

/** What a voice utterance asked for, already parsed (the recorder never sees the words). [intent] is a canvas label word, never a transcript. */
sealed interface VoiceMark {
    data class Mark(val intent: String?) : VoiceMark
    data class Retro(val intent: String?) : VoiceMark
    data object Undo : VoiceMark
}

/**
 * Slice 3a Task 31 (brief M3, S54, S57, S63): marks on `canvas_label`. MOMENT rows (and an INTENT row for a clean intent word) with NO text column.
 *
 * - Glasses tap marks arrive by [onMark] (idempotent by `(epoch, seq)`, which is the label id). A mark whose mapped time is before `wipedAtMs`, or that was taken
 *   under an older deletion counter, is dropped (S57); it is still acknowledged so the glasses stop re-sending it. Every write goes through the fenced DAO, which bumps `labelRev`.
 * - A `listen = true` mark and the glasses' `ListenStart` join within +-[JOIN_MS] (inclusive) of each other, in either order, once ([MarkMessage] and the listen
 *   travel on separate channels, so their order is not guaranteed). The join is by time (no `listenId` in core, LA11).
 * - [applyVoice]: Mark/Retro turn the bound tap mark into a voice mark or create one at the listen start (never two MOMENTs per listen); Undo deletes the bound
 *   mark, else the newest tap/voice mark of the last [UNDO_WINDOW_MS]. [onAsk]: a tap mark whose utterance was an ordinary question becomes `ask_tap`.
 * - Everything is serialised by one mutex. Logs carry event names only. A mark never touches capture.
 */
class MarkRecorder(
    private val dao: CanvasSlice3Dao,
    private val fence: () -> DaoFence,
    private val wallClock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private class Listen(val startAt: Long, var bound: String? = null, val glasses: Boolean = true)
    private class Pending(val id: String, val at: Long)
    private var listen: Listen? = null
    private val unjoined = ArrayList<Pending>()
    private val joinedIds = LinkedHashSet<String>()

    /** Returns once every recorder operation started before it has finished (tests, shutdown). */
    suspend fun awaitIdle() = lock.withLock { }

    /** A glasses `ListenStart` reached the phone at [nowMs] (phone clock). */
    suspend fun onListenStart(nowMs: Long) = lock.withLock {
        val l = Listen(nowMs)
        listen = l
        unjoined.removeAll { nowMs - it.at > PRUNE_MS }
        unjoined.filter { abs(it.at - nowMs) <= JOIN_MS && it.id !in joinedIds }.minByOrNull { abs(it.at - nowMs) }?.let { p ->
            l.bound = p.id; joinedIds += p.id; unjoined.remove(p); log("mark joined listen (mark first)")
        }
    }

    /** A phone-mic listen (no glasses mark can bind to it): anchored where the phone saw it. */
    suspend fun onPhoneListenStart(nowMs: Long) = lock.withLock { listen = Listen(nowMs, glasses = false) }

    /** One `pc_mark`. True = handled (stored, a duplicate, or deliberately dropped): acknowledge it. Throws only when the store failed (do not ack). */
    suspend fun onMark(m: MarkMessage, arrival: MarkArrival): Boolean = lock.withLock {
        val id = labelId(m)
        val f = fence()
        if (m.undo != null) {
            val target = "mk:${m.epoch}:${m.undo}"
            dao.deleteLabel(target); dao.deleteLabel("$target:i")
            unjoined.removeAll { it.id == target }
            if (listen?.bound == target) listen?.bound = null
            log("mark undone")
            return@withLock true
        }
        if (m.deletionCounter >= 0 && m.deletionCounter < f.now().counter) { log("mark dropped: older deletion epoch"); return@withLock true }
        if (dao.label(id) != null) { log("mark already recorded"); return@withLock true } // double delivery: no second row, no join, no labelRev move
        val ok = dao.insertLabelFenced(
            CanvasLabelEntity(id, KIND_MOMENT, SRC_TAP, MOMENT, arrival.mappedAtMs, provenance = if (arrival.mapped) "clock:mapped" else "clock:receipt", deletionCounter = maxOf(m.deletionCounter, 0), createdAt = wallClock()),
            f,
        )
        if (!ok) { log("mark dropped: before the wipe or fence moved"); return@withLock true }
        if (m.listen) {
            val l = listen
            if (l != null && l.glasses && l.bound == null && abs(arrival.mappedAtMs - l.startAt) <= JOIN_MS) { l.bound = id; joinedIds += id; log("mark joined listen (listen first)") }
            else unjoined += Pending(id, arrival.mappedAtMs)
        }
        log("mark recorded")
        true
    }

    /** The recognised utterance carried these commands (in spoken order). [glasses] false = a phone-mic ask. */
    suspend fun applyVoice(commands: List<VoiceMark>, glasses: Boolean, nowMs: Long) = lock.withLock {
        val l = listen?.takeIf { it.glasses == glasses } ?: Listen(nowMs, glasses = glasses).also { listen = it }
        val f = fence()
        val srcVoice = if (glasses) SRC_VOICE else SRC_PHONE_VOICE
        var momentId: String? = null
        for (c in commands) when (c) {
            is VoiceMark.Mark -> momentId = ensureMoment(l, f, srcVoice, momentId, c.intent, nowMs)
            is VoiceMark.Retro -> momentId = ensureMoment(l, f, SRC_RETRO, momentId, c.intent, nowMs)
            VoiceMark.Undo -> {
                val target = momentId ?: l.bound ?: newestUndoable(nowMs)
                if (target != null) {
                    dao.deleteLabel(target); dao.deleteLabel("$target:i")
                    unjoined.removeAll { it.id == target }
                    if (l.bound == target) l.bound = null
                    momentId = null
                    log("mark undone by voice")
                }
            }
        }
    }

    /** The utterance was an ordinary question: a tap mark bound to this listen stays, flagged `ask_tap` (excluded from recall by default). */
    suspend fun onAsk(glasses: Boolean) = lock.withLock {
        val l = listen?.takeIf { it.glasses && glasses } ?: return@withLock
        l.bound?.let { dao.retagLabel(it, SRC_ASK_TAP) }
    }

    private suspend fun ensureMoment(l: Listen, f: DaoFence, source: String, already: String?, intent: String?, nowMs: Long): String? {
        val id = already ?: l.bound ?: "vm:${l.startAt}:${if (l.glasses) 'g' else 'p'}"
        if (already == null) {
            if (l.bound != null) dao.retagLabel(id, source)
            else dao.insertLabelFenced(CanvasLabelEntity(id, KIND_MOMENT, source, MOMENT, l.startAt, provenance = "clock:receipt", deletionCounter = f.now().counter, createdAt = wallClock()), f)
        }
        if (intent != null && dao.label("$id:i") == null) {
            dao.insertLabelFenced(CanvasLabelEntity("$id:i", KIND_INTENT, source, intent, l.startAt, provenance = "intent", deletionCounter = f.now().counter, createdAt = wallClock()), f)
        }
        log("voice mark recorded")
        return id
    }

    private suspend fun newestUndoable(nowMs: Long): String? =
        dao.labelsBetween(nowMs - UNDO_WINDOW_MS, nowMs).lastOrNull { it.kind == KIND_MOMENT && it.source in UNDOABLE }?.labelId

    companion object {
        const val JOIN_MS = 3_000L
        const val UNDO_WINDOW_MS = 120_000L
        private const val PRUNE_MS = 60_000L
        const val KIND_MOMENT = "MOMENT"; const val KIND_INTENT = "INTENT"; const val MOMENT = "MOMENT"
        const val SRC_TAP = "tap"; const val SRC_VOICE = "voice"; const val SRC_PHONE_VOICE = "phone_voice"; const val SRC_RETRO = "retro"; const val SRC_ASK_TAP = "ask_tap"
        private val UNDOABLE = setOf(SRC_TAP, SRC_VOICE, SRC_PHONE_VOICE, SRC_RETRO)
        fun labelId(m: MarkMessage) = "mk:${m.epoch}:${m.seq}"
    }
}
