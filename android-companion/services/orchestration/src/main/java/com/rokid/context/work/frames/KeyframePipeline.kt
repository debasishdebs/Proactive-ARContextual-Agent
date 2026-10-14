package com.rokid.context.work.frames

import android.util.Log
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameStateDao
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.SavedMemory
import com.rokid.context.work.budget.PhonePowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** A frame picked for extraction: [ts] is the offset-corrected capture time, [dwell] a dwell keyframe (frame_meta). */
data class KeyframeCandidate(val frameId: String, val dwell: Boolean, val ts: Long)

/** What one extraction call concluded. [unreadable] frames (file gone, forgotten) are failed for good, whatever the rest did. */
sealed interface ExtractOutcome {
    val unreadable: Set<String>

    /** The memories are saved and the claimed frames are EXTRACTED (one transaction, claim CAS first). */
    data class Saved(val memories: List<SavedMemory>, override val unreadable: Set<String> = emptySet()) : ExtractOutcome

    /** The claim no longer held, or Delete all ran: the output was dropped and nothing was written. */
    data class Stale(override val unreadable: Set<String> = emptySet()) : ExtractOutcome

    /** [countsAttempt] false (offline, no provider set up): the frames go back to PENDING with no attempt used. */
    data class Failed(val error: String, val countsAttempt: Boolean = true, override val unreadable: Set<String> = emptySet()) : ExtractOutcome

    companion object {
        /** Typed so the pipeline can tell "the frame's fault" from "the network or the settings' fault". */
        fun classify(t: Throwable): Failed = when {
            t is ModelCallException && (t.kind == FailureKind.NOT_SET || t.kind == FailureKind.AUTH) -> Failed("provider ${t.kind.name}", countsAttempt = false)
            t is ModelCallException && t.kind == FailureKind.TRANSIENT && t.cause is IOException -> Failed("offline", countsAttempt = false)
            t is IOException -> Failed("offline", countsAttempt = false)
            t is ModelCallException -> Failed(t.kind.name)
            else -> Failed(t.javaClass.simpleName)
        }
    }
}

/**
 * Processes keyframes as they arrive (spec §P2.1): [signal] after a frame commits; the loop claims at most 3 pending frames
 * per call (dwell keyframes first, then newest), extracts them as one VISION_EXTRACT call ([extractor] = ExtractSave.extract
 * then saveExtraction with the claims, so the claim CAS runs inside the save transaction) and settles each frame.
 *
 * - failures: `frame_state.fail` (FAILED, FAILED_FINAL on the 5th); offline / no provider releases the claim without an attempt
 *   and pauses the loop (30 s doubling to 5 min, cleared by [onNetworkReturn]); FAILED frames wait their own backoff;
 * - a crash between claim and commit leaves the claim; [FrameRecovery] requeues it after 5 min (cancellation releases at once);
 * - background work obeys [power] (MODERATE: one non-user extraction per 30 s; SEVERE: paused) and [deferred] (battery saver
 *   or low battery); [extractForAsk] (CallClass.USER) is never paused and skips backoff.
 * The call budget is not here: it sits behind LanguageModel, so [extractor]'s call waits for its token there.
 */
class KeyframePipeline(
    private val db: ContextDatabase,
    private val extractor: suspend (frameIds: List<String>, claimId: String, callClass: CallClass) -> ExtractOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val power: PhonePowerPolicy? = null,
    private val deferred: () -> Boolean = { false },
    val latency: LatencyTracker = LatencyTracker(),
    private val onExtracted: suspend (frames: List<KeyframeCandidate>, memories: List<SavedMemory>) -> Unit = { _, _ -> },
    private val newClaimId: () -> String = { UUID.randomUUID().toString() },
    private val pollMs: Long = 15_000L,
) {
    private sealed interface Step {
        data class Done(val frames: Int) : Step
        /** Nothing to do now; [waitMs] = come back after this long (blocked or backing off), null = nothing is waiting. */
        data class Idle(val waitMs: Long?) : Step
    }

    private val states: FrameStateDao get() = db.frameStateDao()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pausedUntilMs = 0L
    @Volatile private var pauseStepMs = 0L
    @Volatile private var forced = false
    private var loop: Job? = null

    /** A new frame committed (or something became claimable): wakes the loop. Cheap, any thread. */
    fun signal() { wake.trySend(Unit) }

    /** Network is back: lift the offline pause and retry now. */
    fun onNetworkReturn() { forced = true; signal() }

    /** Starts the loop once. */
    fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (loop?.isActive == true) return
            loop = scope.launch {
                while (isActive) {
                    wake.receive()
                    process()
                }
            }
        }
        signal() // whatever is already claimable
    }

    fun stop() { synchronized(this) { loop?.cancel(); loop = null } }

    /** Abandoned claims back to PENDING, then wake the loop. */
    suspend fun recover(): Int = FrameRecovery.run(states, clock(), ::signal)

    /** One pass: claims up to 3 frames (dwell first, then newest) and extracts them. Returns the frames that reached EXTRACTED. */
    suspend fun drainOnce(): Int = (drain(CallClass.EXTRACT, sinceMs = null) as? Step.Done)?.frames ?: 0

    /** Ask with a backlog (spec §P2.3): the newest <= 3 frames from the last [windowMs] go first, as a user-started call. */
    suspend fun extractForAsk(windowMs: Long = ASK_WINDOW_MS): Int =
        (drain(CallClass.USER, sinceMs = clock() - windowMs) as? Step.Done)?.frames ?: 0

    private suspend fun process() {
        while (true) {
            val paused = pausedUntilMs - clock()
            if (paused > 0 && !forced) { sleepOrWake(paused); continue }
            forced = false
            when (val s = drain(CallClass.EXTRACT, sinceMs = null)) {
                is Step.Done -> continue
                is Step.Idle -> sleepOrWake(s.waitMs ?: return)
            }
        }
    }

    private suspend fun sleepOrWake(ms: Long) { withTimeoutOrNull(ms) { wake.receive() } }

    private suspend fun drain(callClass: CallClass, sinceMs: Long?): Step {
        val user = callClass == CallClass.USER
        val now = clock()
        val rows = states.recoverable(now, now - FrameRecovery.CLAIM_TIMEOUT_MS, SCAN)
        if (rows.isEmpty()) return Step.Idle(null)
        var wakeAt: Long? = null
        val ready = rows.filter { r ->
            if (user || r.state != "FAILED") return@filter true
            val at = r.updatedAt + failureBackoffMs(r.attempts)
            if (at > now) { wakeAt = minOf(wakeAt ?: at, at); false } else true
        }
        val infos = candidates(ready.map { it.frameId }, rows.associate { it.frameId to it.updatedAt }).let { c -> sinceMs?.let { s -> c.filter { it.ts >= s } } ?: c }
        val picked = (if (user) infos.sortedByDescending { it.ts } else infos.sortedWith(compareByDescending<KeyframeCandidate> { it.dwell }.thenByDescending { it.ts }))
            .take(MAX_BATCH)
        if (picked.isEmpty()) return Step.Idle(wakeAt?.let { it - now })
        if (!user && (deferred() || power?.tryExtraction() == false)) return Step.Idle(pollMs)

        val claimId = newClaimId()
        val claimed = states.claim(picked.map { it.frameId }, claimId, now, now - FrameRecovery.CLAIM_TIMEOUT_MS).map { it.frameId }.toSet()
        if (claimed.isEmpty()) return Step.Idle(CONTENTION_MS) // the old worker or an ask took them
        val frames = picked.filter { it.frameId in claimed }
        val outcome = try {
            extractor(frames.map { it.frameId }, claimId, callClass)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { frames.forEach { states.release(it.frameId, claimId, clock()) } }
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "extraction failed: ${e.javaClass.simpleName}")
            ExtractOutcome.classify(e)
        }
        return Step.Done(settle(frames, claimId, outcome, user))
    }

    private suspend fun settle(frames: List<KeyframeCandidate>, claimId: String, o: ExtractOutcome, user: Boolean): Int {
        val now = clock()
        frames.filter { it.frameId in o.unreadable }.forEach { states.fail(it.frameId, claimId, "unreadable", now, maxAttempts = 1) }
        val rest = frames.filter { it.frameId !in o.unreadable }
        return when (o) {
            is ExtractOutcome.Saved -> {
                pauseStepMs = 0L; pausedUntilMs = 0L
                rest.forEach { latency.record(now - it.ts) }
                runCatching { onExtracted(rest, o.memories) }.onFailure { Log.w(TAG, "post-extraction hook failed: ${it.javaClass.simpleName}") }
                rest.size
            }
            is ExtractOutcome.Stale -> 0
            is ExtractOutcome.Failed -> {
                rest.forEach { if (o.countsAttempt) states.fail(it.frameId, claimId, o.error, now) else states.release(it.frameId, claimId, now) }
                if (!o.countsAttempt && !user) {
                    pauseStepMs = if (pauseStepMs == 0L) PAUSE_START_MS else minOf(pauseStepMs * 2, PAUSE_MAX_MS)
                    pausedUntilMs = now + pauseStepMs
                }
                0
            }
        }
    }

    private suspend fun candidates(ids: List<String>, updatedAt: Map<String, Long>): List<KeyframeCandidate> {
        val metas = ids.chunked(CHUNK).flatMap { db.frameMetaDao().byIds(it) }.associateBy { it.frameId }
        val ts = ids.chunked(CHUNK).flatMap { db.frameDao().byIds(it) }.associate { it.id to it.ts }
        return ids.map { KeyframeCandidate(it, metas[it]?.dwell ?: false, ts[it] ?: metas[it]?.createdAt ?: updatedAt[it] ?: 0L) }
    }

    companion object {
        private const val TAG = "KeyframePipeline"
        const val MAX_BATCH = 3
        const val ASK_WINDOW_MS = 60_000L
        private const val SCAN = 500
        private const val CHUNK = 500
        private const val CONTENTION_MS = 1_000L
        private const val PAUSE_START_MS = 30_000L
        private const val PAUSE_MAX_MS = 5 * 60_000L

        /** A FAILED frame is retried after 30 s, 60 s, 2 min, 4 min (its 5th failure is final). */
        fun failureBackoffMs(attempts: Int): Long = 30_000L shl (attempts - 1).coerceIn(0, 4)
    }
}
