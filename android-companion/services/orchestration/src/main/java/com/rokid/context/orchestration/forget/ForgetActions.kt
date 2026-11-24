package com.rokid.context.orchestration.forget

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ProposalNotifications
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryMaintenance
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetScopeSetting
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.memory.forget.ScopedForget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.orchestration.DataOpGate

/**
 * Memory detail's Forget fact / Forget moment (spec §2.4, §6.1.1, R-16, R-77). On IO and not cancellable once started
 * (a privacy action: leaving the screen must not stop it part-way). The cascade is one transaction
 * ([ForgetService]); then the listed JPEGs are deleted, the WAL is truncated (deleted pages don't linger), the
 * re-summarise worker is queued when anything was invalidated, and [onForgotten] runs (the hub refresh, so the next
 * StateFrame drops the memory from `today`). False on any failure; nothing is logged but the error's class.
 *
 * In-flight work is fenced elsewhere: a queued job carries ids and re-reads them (forgotten ones dropped), a fact for a
 * tombstoned frame is never written, and a derived write whose source vanished is dropped (PipelineRecords.appendFact).
 */
object ForgetActions {
    private const val TAG = "ForgetActions"

    /**
     * How a Forget ended. [EXPORT_IN_PROGRESS] = a running export held the [DataOpGate] for 30 s: nothing was forgotten, try again (never silently dropped). [SAVE_IN_PROGRESS] = a Canvas Save
     * held it (a save is short): nothing was forgotten, try again. [CACHE_CLEANUP_FAILED] = the Forget COMMITTED but, in a production build, a cached memory export that still holds text could not be
     * deleted: it is retried at the next start and the next Forget, and the caller says what remains. [TOO_LARGE] = the facts connected to a Moment/Scene Forget are beyond the depth/size cap: nothing was deleted. [CONTENT_CHANGED] = a scoped Forget was confirmed against a different set than the one previewed (new photos, marks or Moments in the span, a changed Scene): nothing was deleted, preview again.
     */
    enum class Outcome { DONE, EXPORT_IN_PROGRESS, SAVE_IN_PROGRESS, /** A photo upload held the gate beyond its timeout: nothing was forgotten (not a Canvas save). */ UPLOAD_IN_PROGRESS, CACHE_CLEANUP_FAILED, CONTENT_CHANGED, TOO_LARGE, FAILED,
        /** Slice 1.4.2: the removal of the pins that reference a Moment this Forget deletes could not be prepared: NOTHING was forgotten. */
        PIN_PREP_FAILED,
        /** Slice 1.4.2: a range Forget would delete pins and the caller did not pass the number of pins the user confirmed: nothing was forgotten. */
        PINS_NOT_CONFIRMED,
        /** Slice 1.4.2: the Forget COMMITTED; only the removal of its pins did not finish and is completed at the next start. */
        PIN_CLEANUP_PENDING,
        /** Slice 1.4.2: the Forget COMMITTED but the removal of its pins could not be recorded: the pins stay (they show "Needs a Moment"). */
        PIN_NOT_REMOVED,
        /** The two notices together with a cached export that could not be deleted. */
        CACHE_AND_PIN_PENDING, CACHE_AND_PIN_NOT_REMOVED;
        /** The Forget itself went through (the cached-export leftover does not undo it). */
        val committed: Boolean get() = this != EXPORT_IN_PROGRESS && this != SAVE_IN_PROGRESS && this != UPLOAD_IN_PROGRESS && this != CONTENT_CHANGED && this != TOO_LARGE && this != FAILED && this != PIN_PREP_FAILED && this != PINS_NOT_CONFIRMED
    }

    suspend fun forgetFact(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Boolean = forgetFactOutcome(context, memoryId, onForgotten).committed

    suspend fun forgetMoment(context: Context, frameId: String, onForgotten: () -> Unit = {}): Boolean = forgetMomentOutcome(context, frameId, onForgotten).committed

    suspend fun forgetFactOutcome(context: Context, memoryId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten, { LoggedScope.FACT }, com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.MEMORY, memoryId), target = { emptySet() }) { it.forgetFact(memoryId) } // a fact Forget removes no photo: no pin goes

    suspend fun forgetMomentOutcome(context: Context, frameId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten, { LoggedScope.PHOTO }, com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.PHOTO, frameId), target = scopedTarget(context, ForgetTarget.Photo(frameId), ForgetScope.PHOTO), allowedPins = emptySet()) { it.forgetMoment(frameId) } // no dialog names pins here: none is deleted

    /**
     * The photo Forget of a mark that is undone (glasses voice or tap, and the phone's "Forget this mark"): the same gated Forget as [forgetMomentOutcome], but it NEVER deletes a pin. An undo shows no dialog that
     * names pins, and a pin is deleted only by an explicit Forget that names it. The explicit target is empty, so nothing is armed; the listener sweep then clears the link of a pin whose Moment lost its last photo
     * (the pin stays and shows "Needs a Moment"), exactly as for expiry (coverage map O6).
     */
    suspend fun forgetMomentKeepPinsOutcome(context: Context, frameId: String, onForgotten: () -> Unit = {}): Outcome =
        run(context, onForgotten, { LoggedScope.PHOTO }, com.rokid.context.memory.forget.lifecycle.Ref(com.rokid.context.memory.forget.lifecycle.Family.PHOTO, frameId), target = { emptySet() }) { it.forgetMoment(frameId) }

    /**
     * The granular Forget with the user's SCOPE (Photo | Moment | Scene, [ForgetScopeSetting]): memory detail's Forget fact and Forget photo, and any Moment or Scene entry, go through here. Same gate, same typed
     * outcomes, same post-commit cleanup as every Forget (cached export, reminders, notifications, outbox, queue scrub, listeners); MOMENT and SCENE run the range-Forget code ([com.rokid.context.memory.forget.ScopedForget]).
     * The scope is read ONCE here ([scope] defaults to the setting) so the dialog's counts and the delete name the same scope.
     */
    suspend fun forgetScopedOutcome(context: Context, target: ForgetTarget, scope: ForgetScope = ForgetScopeSetting.get(), expectedHash: String? = null, undoMark: (suspend (String) -> Any?)? = null, confirmedPins: Set<String> = emptySet(), onForgotten: () -> Unit = {}): Outcome =
        if (target is ForgetTarget.Mark) markOutcome(target.labelId, onForgotten, undoMark)
        else {
            var ran: ForgetScope? = null
            run(context, onForgotten, { ran?.let { LoggedScope.valueOf(it.name) } ?: LoggedScope.PHOTO }, target = scopedTarget(context, target, scope), allowedPins = confirmedPins) { svc ->
                val engine = ScopedForget(MemoryGraph.get(context).db, svc)
                val plan = engine.plan(target, scope)
                ran = plan.effective
                engine.forget(plan, expectedHash).cascade
            }
        }

    /**
     * A mark is undone through the recorder's own undo ([undoMark]: the fence, the gated delete, the Moment of its second rebuilt): the same typed outcomes as every Forget. No delete logic here.
     * With no recorder given nothing is removed.
     */
    private suspend fun markOutcome(labelId: String, onForgotten: () -> Unit, undoMark: (suspend (String) -> Any?)?): Outcome {
        if (undoMark == null) return Outcome.FAILED
        return withContext(Dispatchers.IO + NonCancellable) {
            try {
                val photo = undoMark(labelId)
                runCatching { com.rokid.context.memory.forget.ForgetListeners.userForget() } // v2.0: whatever another surface (the Nexus bridge) shows is withdrawn
                runCatching(onForgotten)
                // the mark's photo Forget committed but its notice is the user's to see (a cached export still holds text): never turned into a plain DONE
                if (photo is MarkPhotoOutcome && photo.outcome != Outcome.DONE && photo.outcome.committed) photo.outcome else Outcome.DONE
            } catch (e: DataOpGate.UploadInProgress) {
                Outcome.UPLOAD_IN_PROGRESS
            } catch (e: DataOpGate.WriteInProgress) {
                Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) {
                Outcome.EXPORT_IN_PROGRESS
            } catch (e: Exception) {
                PcLog.w(TAG, "mark forget failed: ${SafeLog.describe(e)}")
                Outcome.FAILED
            }
        }
    }

    /** What a mark undo returns when its photo Forget committed with a notice: the recorder passes the photo Forget's typed [outcome] through (no ids, no text). */
    class MarkPhotoOutcome(val outcome: Outcome)

    /** What [forgetScopedOutcome] would remove, for the confirm dialog (read only, no gate): the scope it really runs at and the counts. */
    data class ScopedPreview(val effective: ForgetScope, val photos: Int, val memories: Int, val marks: Int, val moments: Int, val scenesRemoved: Int, val scenesShrunk: Int, val planHash: String, val outside: Int = 0, val tooLarge: Boolean = false, /** Slice 1.4.2: pins that reference a Moment this Forget removes: they are deleted with it (count and the first names, for the dialog). */ val pins: Int = 0, val pinNames: List<String> = emptyList(), val peerResults: Int = 0, val needsAck: Boolean = false, val ackHash: String = "", /** The ids of exactly the pins the dialog named: the Forget arms only these. */ val pinIds: Set<String> = emptySet(), /** The pin lookup failed: the pins are unknown, so a pin-carrying confirmation never proceeds on it. */ val pinsUnknown: Boolean = false)
    suspend fun previewScoped(context: Context, target: ForgetTarget, scope: ForgetScope = ForgetScopeSetting.get()): ScopedPreview = withContext(Dispatchers.IO) {
        val graph = MemoryGraph.get(context)
        val engine = ScopedForget(graph.db, ForgetService(graph.db, graph.proposals))
        val plan = engine.plan(target, scope); val c = engine.preview(plan)
        val pins = com.rokid.context.work.frames.place.PinMomentLinks.pinsGoing(context) { ids -> engine.momentsReached(plan, ids) }
        ScopedPreview(plan.effective, c.photos, c.memories, c.marks, c.moments, c.scenesRemoved, c.scenesShrunk, c.planHash, c.outside, c.tooLarge, pins.count, pins.names, c.peerResults, c.needsAck, c.ackHash, pins.ids, pins.failed)
    }

    /**
     * What a multi-select Forget (Activity, activity pagination A8, proxy ruling P2) would do, for the confirmation: the planner's own plan of [roots] as a user Forget (read only, no gate), as counts per family plus the
     * token that confirms exactly this set. Null when `forgetV2` is OFF (the feature does not exist then) or the plan could not be made. Never logs an id.
     */
    suspend fun previewSelection(context: Context, roots: Set<com.rokid.context.memory.forget.lifecycle.Ref>): com.rokid.context.memory.forget.lifecycle.SelectionPreview? = withContext(Dispatchers.IO) {
        try {
            val graph = MemoryGraph.get(context) // installs the flag reader
            if (!com.rokid.context.memory.forget.lifecycle.ForgetV2.on) return@withContext null
            val p = ForgetService(graph.db, graph.proposals).previewSelection(roots)
            if (p == null || p.tooLarge || p.erasedPhotos.isEmpty()) p
            else { // slice 1.4.2: the pins whose Moment loses its last photo here go with it: named in the confirmation
                val g = com.rokid.context.work.frames.place.PinMomentLinks.pinsGoing(context) { ids -> momentsFullyErased(graph.db.momentDao(), ids, p.erasedPhotos) }
                if (g.count > 0 || g.failed) p.withPins(g.count, g.names, g.ids, g.failed) else p
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e
        } catch (e: Exception) { PcLog.w(TAG, "selection preview failed: ${SafeLog.describe(e)}"); null }
    }

    /**
     * The multi-select Forget of [roots], confirmed with [token] (the preview's `token(acknowledged)`): the same gate, typed outcomes and post-commit cleanup as every Forget, one planner and one executor. With `forgetV2` OFF
     * nothing runs ([Outcome.FAILED]). [Outcome.CONTENT_CHANGED] = the set differs from the previewed one, or a large selection was confirmed without its acknowledgement: nothing deleted, preview again.
     * [Outcome.TOO_LARGE] = above 5,000 roots or plan nodes: select fewer.
     */
    suspend fun forgetSelectionOutcome(context: Context, roots: Set<com.rokid.context.memory.forget.lifecycle.Ref>, token: String, onForgotten: () -> Unit = {}, pins: Set<String> = emptySet()): Outcome {
        runCatching { MemoryGraph.get(context) } // installs the flag reader
        if (!com.rokid.context.memory.forget.lifecycle.ForgetV2.on) { PcLog.w(TAG, "selection forget not run: forgetV2 is off"); return Outcome.FAILED }
        return run(context, onForgotten, { LoggedScope.SELECTION }, target = selectionTarget(context, roots), allowedPins = pins) { it.forgetSelection(roots, token) } // only the pins the confirmation named (none when its lookup failed)
    }

    /**
     * Set once by the phone at start-up (the hub refresh). Background passes (retention) call it after removing data,
     * so the next StateFrame drops it. The Memory detail route passes its own [onForgotten] as well.
     */
    @Volatile var dataRemoved: () -> Unit = {}

    /**
     * rc1 fix (set once by the phone at start-up): the ids of the asks a committed removal redacted or deleted. An answer still shown for one of them (the phone's voice
     * card, the glasses HUD through the hub's frames) is withdrawn at once, whatever the voice hold or the row watcher did. Called after the commit, on IO; never throws into the Forget.
     */
    @Volatile var asksRedacted: (List<String>) -> Unit = {}

    /** The scope a Forget's milestone line names (a fixed token: FACT and PHOTO for the single-item Forgets, MOMENT/SCENE for the scoped engine, RANGE for a time range). */
    enum class LoggedScope { FACT, PHOTO, MOMENT, SCENE, RANGE, SELECTION }

    /**
     * rc1 fix: ONE milestone line per committed Forget (reaches `pca.log` with diagnostics off), scope and counts only: never an id, a path or any text.
     * photos = phone photo files the Forget removed (a JPEG, not its sidecar); memories = rows deleted; asks = answers redacted.
     */
    internal fun logDone(kind: LoggedScope, r: ForgetService.Result) {
        val photos = r.framesToDelete.filterNot { it.endsWith(".meta.json") }
        PcLog.milestone(TAG, "forget done scope=${kind.name} photos=${photos.size} memories=${r.forgotten.size} invalidated=${r.invalidated.size} asks=${r.redactedAskIds.size}")
    }

    /**
     * Codex r21: moves after every committed removal (a Forget of any kind, each retention chunk, Delete all). The canvas page reads it
     * (GET /api/data-generation) and reads its open view again when it moved, so nothing removed stays on screen there.
     */
    val removals = java.util.concurrent.atomic.AtomicLong(0)

    /** After a committed cascade: files, then the re-summarise. A failure here is logged; the Forget itself succeeded. Returns false when a cached memory export could not be deleted. */
    internal suspend fun apply(context: Context, r: ForgetService.Result): Boolean {
        removals.incrementAndGet() // the cascade is committed
        runCatching { LeaseRevocation.after(r) } // M3: the second line behind the revocation before the delete (retention and range come through here too)
        if (r.redactedAskIds.isNotEmpty()) {
            try { asksRedacted(r.redactedAskIds) } catch (e: Exception) { PcLog.w(TAG, "answer withdrawal not sent: ${SafeLog.describe(e)}") }
        }
        // Integration carry 3: a redacted proposal's notifications still show its text; cancel them in any process.
        // Privacy carry: so does an armed reminder alarm (its intent holds the title and note); cancel it too.
        if (r.redactedProposals.isNotEmpty()) {
            try {
                r.redactedProposals.forEach { ReminderExecutor.cancelAlarm(context, it) }
            } catch (e: Exception) {
                PcLog.w(TAG, "alarms not cancelled: ${SafeLog.describe(e)}")
            }
            try {
                ProposalNotifications.cancel(context, r.redactedProposals)
            } catch (e: Exception) {
                PcLog.w(TAG, "notifications not cancelled: ${SafeLog.describe(e)}")
            }
        }
        FrameSweep.delete(r.framesToDelete)
        // GAP-1: a cached memory export still holds the forgotten text; the next export regenerates it.
        val cacheClean = com.rokid.context.orchestration.MemoryExport.deleteCached(context)
        if (r.invalidated.isNotEmpty()) {
            try {
                ResummarizeWorker.enqueue(context)
            } catch (e: Exception) {
                PcLog.w(TAG, "re-summarise not queued (start-up retries): ${SafeLog.describe(e)}")
            }
        }
        // P1-5: a place-card-history deletion the listener could not finish is retried now. Whether THIS Forget reports it is decided by [cleanupFailed] (scope check): an unrelated, permanently failing token must not fail every Forget.
        try { com.rokid.context.work.frames.place.PlaceGraph.settleHistory(context) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { /* still owed */ }
        return cacheClean
    }

    /**
     * A committed Forget whose cleanup did not finish: a cached export (production builds only) or an owed place-history deletion (always). The history obligations are checked AFTER the pin cascade (which can add
     * one) and finished once more here; a deletion THIS Forget attempted that is still owed means private rows may remain, whatever [clean] said before the cascade. An older failing token (another Forget's) is retried
     * but not reported here (P2-3).
     */
    internal suspend fun cleanupFailed(context: Context, clean: Boolean): Boolean {
        val place = com.rokid.context.work.frames.place.PlaceGraph
        if (place.historyOwed(context)) {
            try { place.settleHistory(context) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { /* still owed */ }
            if (place.historyScopePending()) return true
        }
        return !clean && com.rokid.context.orchestration.MemoryExport.productionBuild(context)
    }

    /** The cleanup after a committed RANGE Forget (the coordinator calls it inside the delete gate): the same as a fact or photo Forget. False = a cached export could not be deleted. */
    suspend fun afterRange(context: Context, r: ForgetService.Result): Boolean {
        logDone(LoggedScope.RANGE, r)
        val clean = apply(context, r)
        scrubQueue(context)
        runCatching { MemoryMaintenance.checkpoint(MemoryGraph.get(context).db) }
        runCatching(dataRemoved)
        return clean
    }

    /** A time-range Forget with the typed outcome (the coordinator must have been built with `afterCascade = { afterRange(ctx, it) }`). */
    suspend fun forgetRangeOutcome(context: Context, coordinator: com.rokid.context.canvas.plan.CanvasForgetCoordinator, fromMs: Long, toMs: Long, confirmedPins: Set<String>? = null): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val inRange = { id: String -> com.rokid.context.memory.moment.MomentId.startMs(id)?.let { it in (Math.floorDiv(maxOf(fromMs, 0L), 1000L) * 1000)..toMs } ?: false }
                // slice 1.4.2: a range Forget that would delete pins runs only with the number of pins the user was shown ([previewRangePins]); no caller can delete pins unannounced
                val going = previewRangePins(context, fromMs, toMs)
                if (rangeNeedsConfirmation(going.ids, confirmedPins)) { PcLog.w(TAG, "range forget not run: pins not confirmed"); return@withContext Outcome.PINS_NOT_CONFIRMED }
                var pending = false; var notRemoved = false
                // only the pins the user confirmed are armed (checked again inside the pin lock): a pin linked after the check is never deleted
                // the arming is stored INSIDE the DataOpGate (with the Forget, not before it)
                val r = DataOpGate.shared.delete { com.rokid.context.work.frames.place.PlaceGraph.beginHistoryScope(); com.rokid.context.work.frames.place.PinMomentLinks.explicit(context, { ids -> ids.filter(inRange).toSet() }, onPending = { pending = true }, onNotRemoved = { notRemoved = true }, allowedPins = confirmedPins ?: emptySet()) { coordinator.forgetRangeLocked(fromMs, toMs) } } // slice 1.4.2: pins of the Moments in the range go with them
                runCatching { com.rokid.context.memory.forget.ForgetListeners.userForget() } // v2.0: whatever another surface (the Nexus bridge) shows is withdrawn
                withPinNotice(if (cleanupFailed(context, r.cacheClean)) Outcome.CACHE_CLEANUP_FAILED else Outcome.DONE, pending, notRemoved)
            } catch (e: com.rokid.context.work.frames.place.PinArmFailed) { Outcome.PIN_PREP_FAILED
            } catch (e: DataOpGate.UploadInProgress) { Outcome.UPLOAD_IN_PROGRESS
            } catch (e: DataOpGate.WriteInProgress) { Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) { Outcome.EXPORT_IN_PROGRESS
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { PcLog.w(TAG, "range forget failed: ${SafeLog.describe(e)}"); Outcome.FAILED }
        }

    /** Slice 1.4.2: a Forget that would delete pins runs only when the caller passes exactly the pin ids its confirmation showed (a set, not a count). */
    internal fun rangeNeedsConfirmation(pins: Set<String>, confirmedPins: Set<String>?): Boolean = pins.isNotEmpty() && confirmedPins != pins

    /** Slice 1.4.2: the pins (count and first names) a range Forget of [fromMs]..[toMs] would delete: what its confirmation must show and pass back as `confirmedPins`. */
    suspend fun previewRangePins(context: Context, fromMs: Long, toMs: Long): com.rokid.context.work.frames.place.PinsGoing {
        val lo = (Math.floorDiv(maxOf(fromMs, 0L), 1000L) * 1000)
        return com.rokid.context.work.frames.place.PinMomentLinks.pinsGoing(context) { ids -> ids.filter { id -> com.rokid.context.memory.moment.MomentId.startMs(id)?.let { it in lo..toMs } ?: false }.toSet() }
    }

    /** Text the queue log still holds in finished rows (fix round 1 I4). */
    internal fun scrubQueue(context: Context) {
        try {
            QueueRuntimeStore.scrubTerminalText(context)
        } catch (e: Exception) {
            PcLog.w(TAG, "queue scrub failed: ${SafeLog.describe(e)}")
        }
    }

    /** Slice 1.4.2: the pin notice of a committed Forget, kept together with a cached-export notice. */
    internal fun withPinNotice(o: Outcome, pending: Boolean, notRemoved: Boolean): Outcome = when {
        !o.committed || (!pending && !notRemoved) -> o
        o == Outcome.CACHE_CLEANUP_FAILED -> if (notRemoved) Outcome.CACHE_AND_PIN_NOT_REMOVED else Outcome.CACHE_AND_PIN_PENDING
        else -> if (notRemoved) Outcome.PIN_NOT_REMOVED else Outcome.PIN_CLEANUP_PENDING
    }

    /** Slice 1.4.2: the Moments (of [ids]) that lose their last photo to [target] at [scope]: the set the confirmation named and the only one whose pins an explicit Forget may delete. */
    private fun scopedTarget(context: Context, target: ForgetTarget, scope: ForgetScope): suspend (List<String>) -> Set<String> = { ids ->
        val g = MemoryGraph.get(context); val e = ScopedForget(g.db, ForgetService(g.db, g.proposals)); e.momentsReached(e.plan(target, scope), ids)
    }

    private fun selectionTarget(context: Context, roots: Set<com.rokid.context.memory.forget.lifecycle.Ref>): suspend (List<String>) -> Set<String> = { ids ->
        val g = MemoryGraph.get(context); val p = ForgetService(g.db, g.proposals).previewSelection(roots)
        if (p == null || p.tooLarge || p.erasedPhotos.isEmpty()) emptySet() else momentsFullyErased(g.db.momentDao(), ids, p.erasedPhotos)
    }

    private suspend fun momentsFullyErased(md: com.rokid.context.memory.db.MomentDao, ids: Collection<String>, erased: Set<String>): Set<String> =
        ids.filter { id -> val s = com.rokid.context.memory.moment.MomentId.parse(id); s != null && md.frameIdsBetween(s * 1000, s * 1000 + 999).let { f -> f.isNotEmpty() && erased.containsAll(f) } }.toSet()

    private suspend fun run(context: Context, onForgotten: () -> Unit, kind: () -> LoggedScope, shadowRoot: com.rokid.context.memory.forget.lifecycle.Ref? = null, target: suspend (List<String>) -> Set<String>, allowedPins: Set<String>? = null, op: suspend (ForgetService) -> ForgetService.Result): Outcome =
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                var pending = false; var notRemoved = false
                val o0 = DataOpGate.shared.delete { com.rokid.context.work.frames.place.PlaceGraph.beginHistoryScope(); com.rokid.context.work.frames.place.PinMomentLinks.explicit(context, target, committed = { o: Outcome -> o.committed }, onPending = { pending = true }, onNotRemoved = { notRemoved = true }, allowedPins = allowedPins) { runLocked(context, onForgotten, kind, shadowRoot, op) } } // slice 1.4.2: a pin whose Moment this Forget removes goes with it
                // Codex place r3 P1-1: the outcome computed inside runLocked predates the pin cascade (explicit() ran it AFTER the block); history rows of a removed pin may still be owed, so a committed DONE is checked again.
                val o = if (o0 == Outcome.DONE && cleanupFailed(context, true)) { PcLog.w(TAG, "forget done, history cleanup pending"); Outcome.CACHE_CLEANUP_FAILED } else o0
                withPinNotice(o, pending, notRemoved)
            } catch (e: com.rokid.context.work.frames.place.PinArmFailed) {
                PcLog.w(TAG, "forget not run: pin removal not prepared")
                Outcome.PIN_PREP_FAILED
            } catch (e: DataOpGate.UploadInProgress) {
                PcLog.w(TAG, "forget not run: photo upload in progress")
                Outcome.UPLOAD_IN_PROGRESS
            } catch (e: DataOpGate.WriteInProgress) {
                PcLog.w(TAG, "forget not run: save in progress")
                Outcome.SAVE_IN_PROGRESS
            } catch (e: DataOpGate.ExportInProgress) {
                PcLog.w(TAG, "forget not run: export in progress")
                Outcome.EXPORT_IN_PROGRESS
            }
        }

    private suspend fun runLocked(context: Context, onForgotten: () -> Unit, kind: () -> LoggedScope, shadowRoot: com.rokid.context.memory.forget.lifecycle.Ref?, op: suspend (ForgetService) -> ForgetService.Result): Outcome {
        // Forget lifecycle M3: the read leases that reach what this Forget touches are revoked BEFORE the delete (no new lease can open, we hold the gate); an unplanned scope revokes them all
        // (with forgetV2 the executor re-plans and revokes inside its own transaction: no call here)
        if (!com.rokid.context.memory.forget.lifecycle.ForgetV2.on) try {
            if (shadowRoot != null) LeaseRevocation.before(MemoryGraph.get(context).db) { it.plan(setOf(shadowRoot), com.rokid.context.memory.forget.lifecycle.Policy.User) } else LeaseRevocation.all("scoped")
        } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (e: Exception) { LeaseRevocation.all("no graph") }
        val shadow = if (shadowRoot != null) ForgetShadow.before(context, shadowRoot) else null // Forget lifecycle M2: reads only, off by default (DeveloperFlags), never affects the Forget
        val r = try {
            val graph = MemoryGraph.get(context)
            op(ForgetService(graph.db, graph.proposals))
        } catch (e: com.rokid.context.memory.forget.ForgetContentChanged) {
            PcLog.w(TAG, "forget not run: content changed")
            return Outcome.CONTENT_CHANGED
        } catch (e: com.rokid.context.memory.forget.ForgetNeedsAck) {
            PcLog.w(TAG, "forget not run: acknowledgement needed") // the dialog opens again with the warning and the acknowledgement
            return Outcome.CONTENT_CHANGED
        } catch (e: com.rokid.context.memory.forget.ForgetTooLarge) {
            PcLog.w(TAG, "forget not run: too large")
            return Outcome.TOO_LARGE
        } catch (e: Exception) {
            PcLog.w(TAG, "forget failed: ${SafeLog.describe(e)}")
            return Outcome.FAILED
        }
        // Committed: everything after this is best effort and never turns the Forget into a failure.
        runCatching { logDone(kind(), r) }
        runCatching { ForgetShadow.after(shadow, kind(), r) }
        val cacheClean = apply(context, r)
        scrubQueue(context)
        runCatching { MemoryMaintenance.checkpoint(MemoryGraph.get(context).db) }
        runCatching { com.rokid.context.memory.forget.ForgetListeners.userForget() } // v2.0: whatever another surface (the Nexus bridge) shows is withdrawn
        runCatching(onForgotten)
        runCatching { com.rokid.context.work.frames.place.PlaceRecovery.kick(context) } // a place memory still marked is retried now
        if (cleanupFailed(context, cacheClean)) {
            PcLog.w(TAG, "forget done, cached export not deleted")
            return Outcome.CACHE_CLEANUP_FAILED
        }
        return Outcome.DONE
    }
}

/**
 * Start-up (fix round 1): sweep photos a failed delete left behind, rebuild anything still INVALIDATED (a re-summarise
 * that never got queued or gave up), and make sure retention and the Moment cadence are scheduled, whatever the pipeline state.
 */
object ForgetStartup {
    suspend fun run(context: Context, refresh: () -> Unit) = withContext(Dispatchers.IO) {
        ForgetActions.dataRemoved = refresh
        runCatching { com.rokid.context.orchestration.MemoryExport.retryPending(context) } // a cached export an earlier Forget could not delete (bounded)
        runCatching { FrameSweep.sweep(context) }
        runCatching { com.rokid.context.work.frames.place.PlaceGraph.settleHistory(context) } // P1-5: a place-history deletion an earlier Forget / Delete all could not finish is owed durably and retried at every start
        runCatching { com.rokid.context.work.frames.place.PinMomentLinks.settle(context) } // slice 1.4.2: a COMMITTED Forget or wipe a crash interrupted finishes its pin cascade; an arming that never committed is dropped
        // v0.9.6: the one-time purge of invalid Moments and orphan memories, BEFORE the cadence is scheduled so a rebuild cannot race it (it stores its own marker and does nothing afterwards)
        try {
            val graph = MemoryGraph.get(context)
            com.rokid.context.canvas.moment.InvalidMomentPurge(graph.db, ForgetService(graph.db, graph.proposals), after = { ForgetActions.apply(context, it) }).runOnce()
        } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { PcLog.i("PcMoments", "invalid purge failed (${e.javaClass.simpleName})") }
        runCatching { if (MemoryGraph.get(context).db.forgetDao().invalidatedDerived(1).isNotEmpty()) ResummarizeWorker.enqueue(context) }
        runCatching { com.rokid.context.work.OrchestrationScheduler(context).ensureBackgroundWork() } // retention and the Moment cadence
        Unit
    }
}
