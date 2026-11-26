package com.rokid.context.canvas.marks

import com.rokid.context.core.log.Level
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.core.log.LogPort
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.core.model.MarkPhotoResult
import com.rokid.context.core.model.MarkRef
import com.rokid.context.memory.FrameIntake
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

/** Where an utterance came from, as far as marks care (v0.9.7 slice D). The glasses variants join the glasses' own tap mark; the phone variants make their own mark and may ask the glasses for a photo. */
enum class MarkOrigin(val glasses: Boolean) { GLASSES_VOICE(true), PHONE_VOICE(false), PHONE_CHAT(false) }

/**
 * v0.9.7 slice D: how a PHONE-origin mark gets its photo. [reasonNow] is a pure, instant decision from the current state (null = a photo can be requested; else a [MarkPhotoReason] token such as
 * `glasses_not_connected`, `glasses_not_worn`, `camera_busy`, `no_photo_support`); [request] sends `pc_mark_photo_request` for the mark's reference (false = it could not be sent now).
 */
interface PhonePhotoRequester {
    fun reasonNow(): String?
    suspend fun request(markRef: String): Boolean
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
    /** Called (never blocking) with the phone-clock time of every mark or intent that was actually stored, so the Moment builder trigger can attach it to its second. */
    private val onRecorded: (Long) -> Unit = {},
    /** Called (never blocking) with the phone-clock time of a mark that was undone or re-tagged, so the Moment of that second is rebuilt (a MARK Moment goes with its mark; the code follows the tag). */
    private val onChanged: (Long) -> Unit = {},
    /**
     * Forgets the photo of a mark that is undone, through the existing gated Forget path (production: `ForgetActions.forgetMomentKeepPinsOutcome`: a pin on the photo's Moment is de-linked, never deleted). False = it could not (a running export held the gate): the undo
     * then throws, is not acknowledged and the glasses resend it. The default (tests of other behaviour) forgets nothing.
     */
    private val forgetPhoto: suspend (frameId: String) -> Boolean = { true },
    /**
     * The same photo Forget with its typed outcome (production: `forgetMomentKeepPinsOutcome`). When set it replaces [forgetPhoto]: an uncommitted outcome refuses the undo exactly as `false` does, and a committed outcome
     * that carries a notice (a cached export that could not be deleted) is handed back by [undoMark] so the phone can show it instead of a plain "done".
     */
    private val forgetPhotoOutcome: (suspend (frameId: String) -> com.rokid.context.orchestration.forget.ForgetActions.Outcome)? = null,
    /**
     * Whether cleanup of an undone mark's photo is STILL owed (production: `ForgetActions.cleanupStillOwed`: retries the cached export / photo file). Used on the retry path of an undo whose photo Forget already
     * committed with a cleanup notice (the link is gone then, so the retry finds no frame): the cache-left outcome is returned until this says clean. Null = a remembered obligation is treated as still owed.
     */
    private val cleanupOwed: (suspend (frameId: String) -> Boolean)? = null,
    /** v0.9.7 slice D: null (tests of other behaviour) = a phone-origin mark has no photo (`phone_mark_no_photo`, as before). */
    private val phonePhoto: PhonePhotoRequester? = null,
    /** v0.9.7 slice D: a phone call is active right now (the existing call detection); a mark made then carries the `call` tag in its provenance. */
    private val callActive: () -> Boolean = { false },
    /** Forget lifecycle M4 (flag forgetV2): the mark undo through the `ForgetExecutor` (production: `ForgetExecutor.deleteMarks(db, ids)`). Null = the legacy DAO calls. */
    private val deleteMarks: (suspend (List<String>) -> Unit)? = null,
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
            undoLocked("mk:${m.epoch}:${m.undo}")
            return@withLock true
        }
        if (m.deletionCounter >= 0 && m.deletionCounter < f.now().counter) { log("mark dropped: older deletion epoch"); return@withLock true }
        if (dao.label(id) != null) { log("mark already recorded"); return@withLock true } // double delivery: no second row, no join, no labelRev move
        // a mark that touches capture (v0.9.6 slice C): `photo` = the glasses will take a photo and say how it went; an old glasses build cannot
        val photo = if (m.photo) MarkPhotoReason.PENDING else MarkPhotoReason.NO_PHOTO_SUPPORT
        val ok = dao.insertLabelFenced(
            CanvasLabelEntity(id, KIND_MOMENT, SRC_TAP, MOMENT, arrival.mappedAtMs, provenance = (if (arrival.mapped) "clock:mapped" else "clock:receipt") + callTag(), deletionCounter = maxOf(m.deletionCounter, 0), createdAt = wallClock(), photo = photo),
            f,
        )
        if (!ok) { log("mark dropped: before the wipe or fence moved"); return@withLock true }
        if (m.listen) {
            val l = listen
            if (l != null && l.glasses && l.bound == null && abs(arrival.mappedAtMs - l.startAt) <= JOIN_MS) { l.bound = id; joinedIds += id; log("mark joined listen (listen first)") }
            else unjoined += Pending(id, arrival.mappedAtMs)
        }
        log("mark recorded")
        onRecorded(arrival.mappedAtMs)
        // the photo may have arrived before its mark: the link is made now, and the second of the photo is rebuilt (its frame commit ran before the mark existed)
        if (photo == MarkPhotoReason.PENDING) dao.linkMarkPhoto(id)?.let { photoAt -> log("mark photo linked"); onRecorded(photoAt) }
        true
    }

    /**
     * Developer IMPORT (`POST /api/import`): restores one mark exactly as exported, with its ORIGINAL id (`mk:<epoch>:<seq>`, `vm:<start>:g` or `mk:phone:<start>`), real kind [source] (tap, voice, phone_voice, retro, ask_tap,
     * phone_chat), time and creation time. The same fenced insert as a live mark (the deletion generation, the wipe time, a forgotten range and an undone mark all refuse it); [photo] is a fixed photo-state token (`ok` is
     * stored as `pending`: the photo of this mark, if it is imported too, links on its frame commit). True = stored or already there. The provenance says `clock:imported`; no intent row is made (the dump carries none).
     */
    suspend fun importMark(id: String, source: String, atMs: Long, photo: String, createdAt: Long): Boolean = lock.withLock {
        if (source !in IMPORTABLE_SOURCES || atMs < 0) return@withLock false
        if (dao.label(id) != null) return@withLock true
        val state = if (photo == MarkPhotoReason.OK) MarkPhotoReason.PENDING else MarkPhotoReason.clean(photo)
        val f = fence()
        val ok = dao.insertLabelFenced(CanvasLabelEntity(id, KIND_MOMENT, source, MOMENT, atMs, provenance = "clock:imported", deletionCounter = f.now().counter, createdAt = createdAt, photo = state), f)
        if (!ok) return@withLock false
        log("mark imported")
        onRecorded(atMs)
        if (state == MarkPhotoReason.PENDING) dao.linkMarkPhoto(id)?.let { at -> onRecorded(at) }
        true
    }

    private suspend fun undoLocked(target: String): com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome? {
        val photo = removeMark(target)
        unjoined.removeAll { it.id == target }
        if (listen?.bound == target) listen?.bound = null
        log("mark undone")
        return photo
    }

    /**
     * The app's "Forget this mark" (the previewer of a photo-less mark): the same undo as a glasses `undo`, through [removeMark] (the fence, the gated delete, the Moment of its second rebuilt). Throws
     * [DataOpGate.ExportInProgress] / [DataOpGate.WriteInProgress] when the gate refuses; nothing is removed then and a retry repeats the steps.
     */
    suspend fun undoMark(labelId: String): com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome? = lock.withLock { undoLocked(labelId) }

    /** Test seams of the undo race (a test commits a photo right after the mark was read / after the fence was written). Null in production. */
    @Volatile internal var testAfterUndoRead: (suspend () -> Unit)? = null
    @Volatile internal var testAfterUndoFence: (suspend () -> Unit)? = null

    /** The glasses could not take the photo of mark `(epoch, seq)` (`pc_mark_photo`). True = the reason was stored (only while the photo is still pending); never throws on a mark we do not have. */
    suspend fun onPhotoResult(r: MarkPhotoResult): Boolean = lock.withLock {
        val id = "mk:${r.epoch}:${r.seq}"
        val at = dao.label(id)?.atMs ?: return@withLock false
        if (!dao.markPhotoFailed(id, MarkPhotoReason.clean(r.reason))) return@withLock false
        log("mark photo failed")
        onRecorded(at)
        true
    }

    /**
     * Removes mark [target] and its intent row and, with them, its PHOTO (the user undid the mark: its photo goes through the existing gated Forget path, tombstone and Moment cleanup included).
     * A photo still on its way is fenced by a `canvas_meta` marker the frame intake checks. Order: the photo first, so a refused Forget throws (nothing removed, the undo is resent) and a retry is idempotent.
     */
    private suspend fun removeMark(target: String): com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome? {
        val l = dao.label(target)
        testAfterUndoRead?.invoke()
        // THE FENCE FIRST, then the link is read again: the frame intake checks the marker inside its own transaction, and the two transactions are serialised, so a photo that committed before the fence is
        // seen by the re-read below (and forgotten) and a photo that commits after it is refused. Reading the link before the fence would leave the photo of a mark undone in between.
        dao.putMeta(FrameIntake.MARK_UNDONE_PREFIX + target, wallClock().toString())
        testAfterUndoFence?.invoke()
        val frame = dao.label(target)?.frameId
        var photo: com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome? = null
        val owedKey = CLEANUP_OWED_PREFIX + target
        if (frame != null) {
            val viaOutcome = forgetPhotoOutcome
            if (viaOutcome != null) {
                val o = viaOutcome(frame)
                if (!o.committed) throw IllegalStateException("photo forget refused") // the marker stays: the resent undo repeats the steps
                photo = com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome(o)
                // the photo Forget committed with a cleanup notice: remembered, because the next step (the gate) can still refuse and the retry will find the link gone
                if (o != com.rokid.context.orchestration.forget.ForgetActions.Outcome.DONE) dao.putMeta(owedKey, frame)
            } else if (!forgetPhoto(frame)) throw IllegalStateException("photo forget refused") // the marker stays: the resent undo repeats the steps
        } else {
            // retry after a refused second gate step: the photo Forget already committed and its cleanup obligation must not be lost
            val owedFrame = dao.meta(owedKey)
            if (owedFrame != null) {
                val check = cleanupOwed
                if (check == null || check(owedFrame)) photo = com.rokid.context.orchestration.forget.ForgetActions.MarkPhotoOutcome(com.rokid.context.orchestration.forget.ForgetActions.Outcome.CACHE_CLEANUP_FAILED)
            }
        }
        // a delete: waits (30 s) for a running export and then throws DataOpGate.ExportInProgress (not acked: the glasses resend the undo)
        DataOpGate.shared.delete {
            val viaExecutor = deleteMarks
            if (com.rokid.context.memory.forget.lifecycle.ForgetV2.on && viaExecutor != null) viaExecutor(listOf(target, "$target:i")) // forgetV2: the executor re-plans, revokes the leases and deletes in one transaction
            else {
                // Forget lifecycle M3: a read lease on the mark (a run or derive over it) is revoked before the delete
                DataOpGate.shared.leases.revoke(com.rokid.context.memory.forget.lifecycle.Leases.marks(listOf(target, "$target:i")))
                dao.deleteLabel(target); dao.deleteLabel("$target:i")
            }
        }
        dao.deleteMetaKeys(listOf(owedKey)) // delivered with this return: the notice is the caller's now
        pruneSettledOwed() // abandoned obligations (an undo never retried) cannot accumulate
        l?.atMs?.let(onChanged)
        return photo
    }

    /** Deletes every settled `mark_cleanup_owed:` key (see [pruneSettledOwed] in the companion). Never throws. */
    private suspend fun pruneSettledOwed() { cleanupOwed?.let { check -> try { pruneSettledOwed(dao, check) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("owed prune failed") } } }

    /** What a phone-origin mark would do about its photo right now: null = ask the glasses, else the reason token. Instant and pure; the caller uses it for the reply and passes it to [applyVoice]. */
    fun phonePhotoDecision(): String? = phonePhoto?.reasonNow() ?: if (phonePhoto == null) MarkPhotoReason.PHONE_MARK else null

    /** The recognised utterance carried these commands (in spoken order). [glasses] false = a phone-mic ask. */
    suspend fun applyVoice(commands: List<VoiceMark>, glasses: Boolean, nowMs: Long): Boolean =
        applyVoice(commands, if (glasses) MarkOrigin.GLASSES_VOICE else MarkOrigin.PHONE_VOICE, nowMs, if (glasses) null else phonePhotoDecision())

    /**
     * The one entry for every surface. A glasses origin joins the tap mark of its listen; a phone origin makes its OWN mark `mk:phone:<start>` (idempotent: the same start never makes a second one) and,
     * when [phonePhotoReason] is null, asks the glasses for its photo through [phonePhoto] (one request per new mark; a request that cannot be sent leaves `glasses_not_connected`). [PHONE_CHAT][MarkOrigin.PHONE_CHAT]
     * never reuses an earlier listen.
     */
    suspend fun applyVoice(commands: List<VoiceMark>, origin: MarkOrigin, nowMs: Long, phonePhotoReason: String?): Boolean = lock.withLock {
        var cleanupLeft = false
        val glasses = origin.glasses
        val l = (if (origin == MarkOrigin.PHONE_CHAT) null else listen?.takeIf { it.glasses == glasses }) ?: Listen(nowMs, glasses = glasses).also { if (origin != MarkOrigin.PHONE_CHAT) listen = it }
        val f = fence()
        val srcVoice = when (origin) { MarkOrigin.GLASSES_VOICE -> SRC_VOICE; MarkOrigin.PHONE_VOICE -> SRC_PHONE_VOICE; MarkOrigin.PHONE_CHAT -> SRC_PHONE_CHAT }
        var momentId: String? = null
        for (c in commands) when (c) {
            is VoiceMark.Mark -> momentId = ensureMoment(l, f, srcVoice, momentId, c.intent, nowMs, phonePhotoReason)
            is VoiceMark.Retro -> momentId = ensureMoment(l, f, SRC_RETRO, momentId, c.intent, nowMs, phonePhotoReason)
            VoiceMark.Undo -> {
                val target = momentId ?: l.bound ?: newestUndoable(nowMs)
                if (target != null) {
                    val undone = removeMark(target) // typed ExportInProgress when an export blocks it (the caller logs it); the mark's photo goes with it
                    unjoined.removeAll { it.id == target }
                    if (l.bound == target) l.bound = null
                    momentId = null
                    if (undone != null && undone.outcome != com.rokid.context.orchestration.forget.ForgetActions.Outcome.DONE && undone.outcome.committed) cleanupLeft = true
                    log("mark undone by voice")
                }
            }
        }
        cleanupLeft
    }

    /** The utterance was an ordinary question: a tap mark bound to this listen stays, flagged `ask_tap` (excluded from recall by default). */
    suspend fun onAsk(glasses: Boolean) = lock.withLock {
        val l = listen?.takeIf { it.glasses && glasses } ?: return@withLock
        l.bound?.let { id -> val at = dao.label(id)?.atMs; dao.retagLabel(id, SRC_ASK_TAP); at?.let(onChanged) }
    }

    private suspend fun ensureMoment(l: Listen, f: DaoFence, source: String, already: String?, intent: String?, nowMs: Long, phonePhotoReason: String?): String? {
        val id = already ?: l.bound ?: if (l.glasses) "vm:${l.startAt}:g" else MarkRef.labelId(MarkRef.of(PHONE_EPOCH, l.startAt))
        if (already == null) {
            if (l.bound != null) { val at = dao.label(id)?.atMs; dao.retagLabel(id, source); at?.let(onChanged) }
            else if (dao.label(id) == null) {
                val reason = if (source == SRC_RETRO) MarkPhotoReason.RETRO else if (l.glasses) MarkPhotoReason.NO_PHOTO_SUPPORT else phonePhotoReason ?: MarkPhotoReason.PENDING
                if (dao.insertLabelFenced(CanvasLabelEntity(id, KIND_MOMENT, source, MOMENT, l.startAt, provenance = "clock:receipt" + callTag(), deletionCounter = f.now().counter, createdAt = wallClock(), photo = reason), f)) {
                    onRecorded(l.startAt)
                    if (reason == MarkPhotoReason.PENDING) requestPhoto(id, l.startAt)
                }
            }
        }
        if (intent != null && dao.label("$id:i") == null) {
            // the intent row follows its mark: same photo link, same state (a mark with no glasses photo possible says why)
            val parent = dao.label(id)
            if (dao.insertLabelFenced(CanvasLabelEntity("$id:i", KIND_INTENT, source, intent, l.startAt, frameId = parent?.frameId, provenance = "intent", deletionCounter = f.now().counter, createdAt = wallClock(), photo = parent?.photo ?: noPhotoReasonOf(l, source)), f)) onRecorded(l.startAt)
        }
        log("voice mark recorded")
        return id
    }

    /** The one photo request of a new phone-origin mark. A request that cannot be sent now makes the mark's reason `glasses_not_connected` (the mark itself stays). */
    private suspend fun requestPhoto(labelId: String, startAt: Long) {
        val sent = try { phonePhoto?.request(MarkRef.of(PHONE_EPOCH, startAt)) ?: false } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
        if (sent) { log("mark photo requested"); return }
        if (dao.markPhotoFailed(labelId, MarkPhotoReason.GLASSES_NOT_CONNECTED)) { log("mark photo request not sent"); onRecorded(startAt) }
    }

    private fun callTag(): String = if (try { callActive() } catch (e: Exception) { false }) CALL_TAG else ""

    /** A voice mark with no glasses tap behind it cannot have a photo: a retro mark (the moment is past), a phone-mic mark (no camera here), or a glasses utterance whose tap mark never arrived (old glasses). */
    private fun noPhotoReasonOf(l: Listen, source: String): String = when {
        source == SRC_RETRO -> MarkPhotoReason.RETRO
        !l.glasses -> MarkPhotoReason.PHONE_MARK
        else -> MarkPhotoReason.NO_PHOTO_SUPPORT
    }

    private suspend fun newestUndoable(nowMs: Long): String? =
        dao.labelsBetween(nowMs - UNDO_WINDOW_MS, nowMs).lastOrNull { it.kind == KIND_MOMENT && it.source in UNDOABLE }?.labelId

    companion object {
        /** The production log: event names only, through the one logger (pca.log), never android.util.Log. */
        val pcLog: (String) -> Unit = logTo(PcLog)
        fun logTo(port: LogPort): (String) -> Unit = { port.log(Level.I, "PcMarks", it) }
        /** `canvas_meta` key (no schema change): an undone mark whose photo Forget committed with a cleanup notice not yet handed to the caller. The value is the photo's frame id. */
        const val CLEANUP_OWED_PREFIX = "mark_cleanup_owed:"
        private const val OWED_PRUNE_BATCH = 50

        /**
         * Prunes settled cleanup obligations: a key whose frame has no row and for which [stillOwed] (no photo file or sidecar left, no cached export pending) says clean is deleted; an unsettled one is kept.
         * A failing [stillOwed] keeps the key. Production callers: the end of every mark undo and the daily retention pass. Returns how many keys were deleted.
         */
        suspend fun pruneSettledOwed(dao: CanvasSlice3Dao, stillOwed: suspend (frameId: String) -> Boolean): Int {
            val settled = dao.owedCleanupWithoutFrame(OWED_PRUNE_BATCH).filter { m ->
                try { !stillOwed(m.value) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            }.map { it.key }
            return if (settled.isEmpty()) 0 else dao.deleteMetaKeys(settled)
        }
        const val JOIN_MS = 3_000L
        const val UNDO_WINDOW_MS = 120_000L
        private const val PRUNE_MS = 60_000L
        const val KIND_MOMENT = "MOMENT"; const val KIND_INTENT = "INTENT"; const val MOMENT = "MOMENT"
        const val SRC_TAP = "tap"; const val SRC_VOICE = "voice"; const val SRC_PHONE_VOICE = "phone_voice"; const val SRC_RETRO = "retro"; const val SRC_ASK_TAP = "ask_tap"
        /** v0.9.7 slice D: a mark made in the Home chat window (never shown as a label; only the `call` tag is). */
        const val SRC_PHONE_CHAT = "phone_chat"
        /** The epoch of a phone-origin mark reference (`phone:<start ms>`); a glasses epoch is a UUID, so the two never collide. */
        const val PHONE_EPOCH = "phone"
        /** Appended to a mark's provenance when a phone call was active as it was made. */
        const val CALL_TAG = ";call"
        /** The kinds the import restores (every kind the dump emits). */
        val IMPORTABLE_SOURCES = setOf(SRC_TAP, SRC_VOICE, SRC_PHONE_VOICE, SRC_RETRO, SRC_ASK_TAP, SRC_PHONE_CHAT)
        private val UNDOABLE = setOf(SRC_TAP, SRC_VOICE, SRC_PHONE_VOICE, SRC_PHONE_CHAT, SRC_RETRO)
        fun labelId(m: MarkMessage) = "mk:${m.epoch}:${m.seq}"
    }
}
