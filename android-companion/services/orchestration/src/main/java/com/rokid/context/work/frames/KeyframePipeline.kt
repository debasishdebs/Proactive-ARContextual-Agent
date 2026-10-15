package com.rokid.context.work.frames

import android.util.Log
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameStateDao
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.SavedMemory
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.budget.PhonePowerPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.rokid.context.orchestration.RetryClassifier
import com.rokid.context.orchestration.RetryPolicy
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** A frame picked for extraction: [ts] is the offset-corrected capture time, [dwell] a dwell keyframe (frame_meta). */
data class KeyframeCandidate(val frameId: String, val dwell: Boolean, val ts: Long, val receivedAt: Long? = null)

/**
 * What one extraction call concluded. [unreadable] frames (row gone, file gone, forgotten) are failed for good, whatever the rest
 * did; [unseen] frames (the file is there but could not be opened right now: Keystore unseal failed, crypto conversion running)
 * take a normal failure with an attempt and wait their backoff (review I3).
 */
sealed interface ExtractOutcome {
    val unreadable: Set<String>
    val unseen: Set<String>

    /** The memories are saved and the claimed frames are EXTRACTED (one transaction, claim CAS first). */
    data class Saved(val memories: List<SavedMemory>, override val unreadable: Set<String> = emptySet(), override val unseen: Set<String> = emptySet()) : ExtractOutcome

    /** The claim no longer held, or Delete all ran: the output was dropped and nothing was written. */
    data class Stale(override val unreadable: Set<String> = emptySet(), override val unseen: Set<String> = emptySet()) : ExtractOutcome

    /**
     * [countsAttempt] false (offline, no provider set up): the frames go back to PENDING with no attempt used.
     * [maxAttempts]: the failure that ends in FAILED_FINAL; transient failures (429, 5xx, timeouts) get [RetryPolicy.MAX_ATTEMPTS]
     * on today's 30 s doubling schedule (about an hour), the frame's own failures 5.
     */
    data class Failed(
        val error: String, val countsAttempt: Boolean = true, override val unreadable: Set<String> = emptySet(),
        override val unseen: Set<String> = emptySet(), val maxAttempts: Int = FrameStateDao.MAX_ATTEMPTS,
    ) : ExtractOutcome

    companion object {
        /**
         * Typed so the pipeline can tell "the frame's fault" from "the network's or the settings' fault". The WHOLE cause chain is
         * read (a SUMMARIZE fallback wraps a ModelCallException in another one). A timeout (a slow provider answered nothing) is NOT
         * offline: it uses an attempt. [online] false (no validated network, from ConnectivityManager) makes a transient failure offline.
         */
        fun classify(t: Throwable, online: Boolean = true): Failed {
            val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(CHAIN_DEPTH).toList()
            val model = chain.filterIsInstance<ModelCallException>()
            val transient = RetryClassifier.classify(t) == RetryClassifier.Outcome.RETRY
            val attempts = if (transient) RetryPolicy.MAX_ATTEMPTS else FrameStateDao.MAX_ATTEMPTS
            return when {
                model.any { it.kind == FailureKind.NOT_SET || it.kind == FailureKind.AUTH } ->
                    Failed("provider ${model.first { it.kind == FailureKind.NOT_SET || it.kind == FailureKind.AUTH }.kind.name}", countsAttempt = false)
                chain.any { it is InterruptedIOException } -> Failed("timeout", maxAttempts = attempts) // SocketTimeoutException and OkHttp's call timeout
                chain.any { it is IOException } -> Failed("offline", countsAttempt = false)
                transient && !online -> Failed("offline", countsAttempt = false)
                t is ModelCallException -> Failed(t.kind.name, maxAttempts = attempts)
                else -> Failed(t.javaClass.simpleName, maxAttempts = attempts)
            }
        }

        private const val CHAIN_DEPTH = 8
    }
}

/**
 * Processes keyframes as they arrive (spec §P2.1): [signal] after a frame commits; the loop claims at most 3 pending frames
 * per call (dwell keyframes first, then newest), extracts them as one VISION_EXTRACT call ([extractor] = ExtractSave.extract
 * then saveExtraction with the claims, so the claim CAS runs inside the save transaction) and settles each frame.
 *
 * - failures: `frame_state.fail` (FAILED, then FAILED_FINAL on the last attempt: 5, or 8 on today's retry schedule for transient
 *   failures); offline / no provider releases the claim without an attempt and pauses the loop (30 s doubling to 5 min,
 *   cleared by [onNetworkReturn]); FAILED frames wait their own backoff;
 * - a stale save (a photo forgotten mid-call, Delete all) gives every other claim of the batch back with no attempt;
 * - a crash between claim and commit leaves the claim; [FrameRecovery] requeues it after [FrameRecovery.CLAIM_TIMEOUT_MS]
 *   (cancellation releases at once);
 * - background work obeys [power] (MODERATE: one non-user extraction per 30 s; SEVERE: paused) and [deferred] (battery saver
 *   or low battery); both are checked before any database scan, and a blocked loop looks again after 60 s ([deferPollMs]) at
 *   the most for the battery and [pollMs] for heat; [extractForAsk] (CallClass.USER) is never paused and skips backoff.
 * The call budget is not here: it sits behind LanguageModel, so [extractor]'s call waits for its token there.
 */
class KeyframePipeline(
    private val db: ContextDatabase,
    private val extractor: suspend (frameIds: List<String>, claimId: String, callClass: CallClass) -> ExtractOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val power: PhonePowerPolicy? = null,
    private val deferred: () -> Boolean = { false },
    /** False only when ConnectivityManager says there is no validated network: transient failures then count as offline. */
    private val online: () -> Boolean = { true },
    val latency: LatencyTracker = LatencyTracker(),
    private val onExtracted: suspend (frames: List<KeyframeCandidate>, memories: List<SavedMemory>) -> Unit = { _, _ -> },
    private val newClaimId: () -> String = { UUID.randomUUID().toString() },
    private val pollMs: Long = 30_000L,
    private val deferPollMs: Long = 60_000L,
    /** True for the process-wide pipeline: before its first claim or recovery, every leftover EXTRACTING row is released (no live claim can exist yet). */
    private val releaseAtStart: Boolean = false,
) {
    private sealed interface Step {
        data class Done(val frames: Int) : Step
        /** Nothing to do now; [waitMs] = come back after this long, null = nothing is waiting; [blocked] = held back by heat or battery. */
        data class Idle(val waitMs: Long?, val blocked: Boolean = false) : Step
    }

    private val states: FrameStateDao get() = db.frameStateDao()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pausedUntilMs = 0L
    @Volatile private var pauseStepMs = 0L
    @Volatile private var forced = false
    private var loop: Job? = null
    private val startRelease = Mutex()
    private var startReleased = false

    /** Once per pipeline (= per process): leftover claims of a dead process go back to PENDING now, not after the 15-minute timeout. */
    private suspend fun ensureStartRelease() {
        if (!releaseAtStart) return
        startRelease.withLock {
            if (startReleased) return
            val n = FrameRecovery.releaseAtProcessStart(states, clock())
            startReleased = true
            Log.i(PIPE_TAG, "released=$n at process start")
        }
    }

    /** A new frame committed (or something became claimable): wakes the loop. Cheap, any thread. */
    fun signal() { wake.trySend(Unit) }

    /** Network is back: lift the offline pause and retry now. */
    fun onNetworkReturn() { forced = true; signal() }

    /** Starts the loop once (idempotent: no recovery, no scan; the caller recovers at start, on network return and in the backstop). */
    fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (loop?.isActive == true) return
            loop = scope.launch {
                while (isActive) {
                    wake.receive()
                    ensureStartRelease()
                    process()
                }
            }
        }
        signal() // whatever is already claimable
    }

    /** Whether the loop is running (the app, the hub service or a signal started it). */
    fun isRunning(): Boolean = loop?.isActive == true

    fun stop() { synchronized(this) { loop?.cancel(); loop = null } }

    /** Abandoned claims back to PENDING, then wake the loop. */
    suspend fun recover(): Int { ensureStartRelease(); return FrameRecovery.run(states, clock(), ::signal) }

    /** One pass: claims up to 3 frames (dwell first, then newest) and extracts them. Returns the frames that reached EXTRACTED. */
    suspend fun drainOnce(): Int = (drainStarted(CallClass.EXTRACT, sinceMs = null) as? Step.Done)?.frames ?: 0

    /**
     * Drains the backlog from the caller's own coroutine (the 15-minute worker, review I5: WorkManager keeps its process awake, a
     * detached loop gets frozen), for at most [maxMs] of elapsed time. Stops when idle, blocked (heat, battery, offline pause) or the
     * next retry is further away than the time left. Returns the frames that reached EXTRACTED.
     */
    suspend fun drainUntilIdle(maxMs: Long): Int {
        val start = System.nanoTime()
        fun left() = maxMs - (System.nanoTime() - start) / 1_000_000L
        var total = 0
        ensureStartRelease()
        while (left() > 0) {
            if (pausedUntilMs - clock() > 0 && !forced) break
            forced = false
            when (val s = drain(CallClass.EXTRACT, sinceMs = null)) {
                is Step.Done -> total += s.frames
                is Step.Idle -> {
                    val w = s.waitMs ?: break
                    if (s.blocked || w > left()) break // heat and battery holds are the loop's to poll; a retry that is due soon is worth waiting for
                    delay(w)
                }
            }
        }
        return total
    }

    /**
     * Ask with a backlog (spec §P2.3): the newest <= 3 frames from the last [windowMs] go first, as a user-started call. Frames in
     * that window that the loop already holds (its call may be waiting for a budget token) are waited for, up to [waitMs] and the
     * caller's own cap, so the ask still sees their memories (review I4).
     */
    suspend fun extractForAsk(windowMs: Long = ASK_WINDOW_MS, waitMs: Long = ASK_WAIT_MS): Int {
        ensureStartRelease()
        val since = clock() - windowMs
        val done = (drain(CallClass.USER, sinceMs = since) as? Step.Done)?.frames ?: 0
        withTimeoutOrNull(waitMs) {
            while (states.liveClaimsSince(clock() - FrameRecovery.CLAIM_TIMEOUT_MS, since) > 0) delay(ASK_POLL_MS)
        }
        return done
    }

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

    private suspend fun drainStarted(callClass: CallClass, sinceMs: Long?): Step { ensureStartRelease(); return drain(callClass, sinceMs) }

    private suspend fun drain(callClass: CallClass, sinceMs: Long?): Step {
        val user = callClass == CallClass.USER
        val now = clock()
        val abandonBefore = now - FrameRecovery.CLAIM_TIMEOUT_MS
        if (!user) {
            // Cheap checks first: no battery or heat gate may cost a table scan, and a blocked loop polls rarely (review M7).
            val wait = when {
                deferred() -> deferPollMs
                power?.mayRunBackground(BackgroundWork.EXTRACTION) == false -> pollMs
                else -> null
            }
            if (wait != null) return if (states.newestClaimable(now, abandonBefore, Long.MIN_VALUE, 0, 1).isEmpty()) Step.Idle(null) else Step.Idle(wait, blocked = true)
        }
        val rows = if (user) {
            states.newestClaimable(now, abandonBefore, sinceMs ?: Long.MIN_VALUE, 1, MAX_BATCH)
        } else {
            // Dwell keyframes first, then newest: the newest 3 of each kind cover any backlog size (review I4).
            (states.newestDwellClaimable(now, abandonBefore, MAX_BATCH) + states.newestClaimable(now, abandonBefore, Long.MIN_VALUE, 0, MAX_BATCH)).distinctBy { it.frameId }
        }
        if (rows.isEmpty()) {
            val retryAt = if (user) null else states.nextRetryAt()
            return Step.Idle(retryAt?.let { (it - now).coerceAtLeast(1L) })
        }
        val infos = candidates(rows.map { it.frameId }, rows.associate { it.frameId to it.updatedAt }).let { c -> sinceMs?.let { s -> c.filter { it.ts >= s } } ?: c }
        val picked = (if (user) infos.sortedByDescending { it.ts } else infos.sortedWith(compareByDescending<KeyframeCandidate> { it.dwell }.thenByDescending { it.ts }))
            .take(MAX_BATCH)
        if (picked.isEmpty()) return Step.Idle(null)
        if (!user && power?.tryExtraction() == false) return Step.Idle(pollMs, blocked = true)

        val claimId = newClaimId()
        val claimed = states.claim(picked.map { it.frameId }, claimId, now, abandonBefore).map { it.frameId }.toSet()
        if (claimed.isEmpty()) return Step.Idle(CONTENTION_MS) // the old worker or an ask took them
        val frames = picked.filter { it.frameId in claimed }
        Log.i(PIPE_TAG, "claimed=${frames.size} batch=${picked.size} class=${callClass.name}")
        val outcome = try {
            extractor(frames.map { it.frameId }, claimId, callClass)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { frames.forEach { states.release(it.frameId, claimId, clock()) } }
            Log.i(PIPE_TAG, "released=${frames.size} cancelled")
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "extraction failed: ${e.javaClass.simpleName}")
            ExtractOutcome.classify(e, online())
        }
        return Step.Done(settle(frames, claimId, outcome, user))
    }

    private suspend fun settle(frames: List<KeyframeCandidate>, claimId: String, o: ExtractOutcome, user: Boolean): Int {
        val now = clock()
        frames.filter { it.frameId in o.unreadable }.forEach { states.fail(it.frameId, claimId, "unreadable", now, maxAttempts = 1) }
        frames.filter { it.frameId in o.unseen && it.frameId !in o.unreadable }.forEach { states.fail(it.frameId, claimId, "unreadable", now) }
        val rest = frames.filter { it.frameId !in o.unreadable && it.frameId !in o.unseen }
        return when (o) {
            is ExtractOutcome.Saved -> {
                pauseStepMs = 0L; pausedUntilMs = 0L
                rest.forEach {
                    val lag = it.receivedAt?.let { r -> r - it.ts }
                    latency.record(now - it.ts, lag)
                    if (lag != null && lag <= LatencyTracker.MAX_RECEIPT_LAG_MS) Log.i(PIPE_TAG, "latencyMs=${(now - it.ts).coerceAtLeast(0L)}")
                }
                Log.i(PIPE_TAG, "extracted=${rest.size}")
                runCatching { onExtracted(rest, o.memories) }.onFailure { Log.w(TAG, "post-extraction hook failed: ${it.javaClass.simpleName}") }
                rest.size
            }
            is ExtractOutcome.Stale -> {
                // One claim failed the CAS and rolled the whole save back: give back every other claim of the batch with no attempt.
                // release() is claim-guarded, so the stale frame itself (retaken, forgotten) is left alone.
                withContext(NonCancellable) { rest.forEach { states.release(it.frameId, claimId, clock()) } }
                Log.i(PIPE_TAG, "released=${rest.size} stale")
                0
            }
            is ExtractOutcome.Failed -> {
                rest.forEach { if (o.countsAttempt) states.fail(it.frameId, claimId, o.error, now, o.maxAttempts) else states.release(it.frameId, claimId, now) }
                if (o.countsAttempt) {
                    val attempts = rest.maxOfOrNull { states.get(it.frameId)?.attempts ?: 0 } ?: 0
                    Log.i(PIPE_TAG, "failed class=${o.error} frames=${rest.size} attempts=$attempts")
                } else Log.i(PIPE_TAG, "released=${rest.size} reason=${o.error}")
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
        return ids.map { KeyframeCandidate(it, metas[it]?.dwell ?: false, ts[it] ?: metas[it]?.createdAt ?: updatedAt[it] ?: 0L, metas[it]?.createdAt) }
    }

    companion object {
        private const val TAG = "KeyframePipeline"
        /** Count-only audit log tag (ids, counts, class names; never captions, text or paths). */
        private const val PIPE_TAG = "PcPipeline"
        const val MAX_BATCH = 3
        const val ASK_WINDOW_MS = 60_000L
        /** An ask waits this long for frames the loop holds (the caller's own cap is 30 s as well). */
        const val ASK_WAIT_MS = 30_000L
        private const val ASK_POLL_MS = 100L
        private const val CHUNK = 500
        private const val CONTENTION_MS = 1_000L
        private const val PAUSE_START_MS = 30_000L
        private const val PAUSE_MAX_MS = 5 * 60_000L
        private const val BACKOFF_MAX_MS = 30 * 60_000L

        /** A FAILED frame is retried after 30 s, 60 s, 2 min, 4 min ... (today's job schedule, capped at 30 min); the last attempt is final. Mirrors FrameStateDao's SQL. */
        fun failureBackoffMs(attempts: Int): Long = minOf(30_000L shl (attempts - 1).coerceIn(0, 20), BACKOFF_MAX_MS)
    }
}
