package com.rokid.context.work.frames

import android.util.Log
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.contract.ClusterContract
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameClusterDao
import com.rokid.context.memory.db.FrameState
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.FrameStateDao
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.SavedMemory
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.frames.scene.SceneIndexer
import org.json.JSONArray
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
 * What one scene-cluster call concluded (wave 2). The clusters were claimed on `frame_cluster` (SUMMARIZING); the pipeline settles them.
 */
sealed interface ClusterOutcome {
    /**
     * The summaries are saved ([ClusterSave]: one transaction, cluster CAS first). [failedFinal] were failed for good by the save itself
     * (no representative left). [leftOut]: clusters the call returned no valid summary for (the pipeline fails each, an attempt);
     * [gone]: clusters none of whose photos can be read any more (failed for good).
     */
    data class Saved(
        val memories: List<SavedMemory>, val failedFinal: Set<String> = emptySet(), val leftOut: Set<String> = emptySet(), val gone: Set<String> = emptySet(),
    ) : ClusterOutcome

    /** A cluster claim no longer held, or Delete all ran: nothing was written. */
    data object Stale : ClusterOutcome

    /** As [ExtractOutcome.Failed], for every cluster of the call. */
    data class Failed(val error: String, val countsAttempt: Boolean = true, val maxAttempts: Int = FrameStateDao.MAX_ATTEMPTS) : ClusterOutcome
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
    /** Wall clock of this process's start: only claims older than it belong to a dead process (fix wave I5). */
    private val processStartMs: () -> Long = { ProcessStart.wallMs },
    /** Wave 2: routes keyframes into the fast path, near-dups and scene clusters before any claim. Null: wave-1 behaviour (every frame is claimable). */
    private val indexer: SceneIndexer? = null,
    /** Wave 2: one CLUSTER_SUMMARY call over the claimed [clusters] with [reps] (cluster id to the representatives to send), saved with the cluster CAS. */
    private val clusterExtractor: (suspend (clusters: List<FrameClusterEntity>, reps: Map<String, List<String>>, claimId: String, callClass: CallClass) -> ClusterOutcome)? = null,
) {
    private sealed interface Step {
        data class Done(val frames: Int) : Step
        /** Nothing to do now; [waitMs] = come back after this long, null = nothing is waiting; [blocked] = held back by heat or battery. */
        data class Idle(val waitMs: Long?, val blocked: Boolean = false) : Step
    }

    private val states: FrameStateDao get() = db.frameStateDao()
    private val clusters: FrameClusterDao get() = db.frameClusterDao()
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
            val n = FrameRecovery.releaseAtProcessStart(states, clock(), processStartMs(), clusters)
            startReleased = true
            Log.i(PIPE_TAG, "released=$n at process start")
            sweepFinalClusters(clock())
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
    suspend fun recover(): Int { ensureStartRelease(); val n = FrameRecovery.run(states, clock(), clusters, ::signal); sweepFinalClusters(clock()); return n }

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
            if (wait != null) return if (anythingWaiting(now, abandonBefore)) Step.Idle(wait, blocked = true) else Step.Idle(null)
        }
        // Wave 2 (Ruling 3): routing runs before any claim; an ask routes at once, ignoring the background batching.
        val routeRetry = route(force = user)
        if (!user) shedBacklog(now)

        if (user) {
            val rows = (states.askCandidates(sinceMs ?: Long.MIN_VALUE, MAX_BATCH) + states.newestClaimable(now, abandonBefore, sinceMs ?: Long.MIN_VALUE, 1, MAX_BATCH)).distinctBy { it.frameId }
            if (rows.isEmpty()) return Step.Idle(null)
            val infos = candidates(rows.map { it.frameId }, rows.associate { it.frameId to it.updatedAt }).let { c -> sinceMs?.let { s -> c.filter { it.ts >= s } } ?: c }
            val picked = infos.sortedByDescending { it.ts }.take(MAX_BATCH)
            if (picked.isEmpty()) return Step.Idle(null)
            return runBatch(picked, callClass, user = true) { ids, claimId -> states.claimForAsk(ids, claimId, now); states.claim(ids, claimId, now, abandonBefore).map { it.frameId } }
        }

        // Pick, in order: dwell PENDING, caption-queued frames waited >= 10 min, newest PENDING, a cluster call, the rest of the caption queue.
        val dwell = states.newestDwellClaimable(now, abandonBefore, MAX_BATCH)
        val captionsOk = power?.mayRunBackground(BackgroundWork.CAPTION_QUEUE) != false
        val aged = if (dwell.isEmpty() && captionsOk) states.captionQueued(MAX_BATCH).filter { now - it.updatedAt >= CAPTION_AGE_MS } else emptyList()
        if (aged.isNotEmpty()) captionStep(aged, now)?.let { return it }
        val rows = (dwell + states.newestClaimable(now, abandonBefore, Long.MIN_VALUE, 0, MAX_BATCH)).distinctBy { it.frameId }
        if (rows.isNotEmpty()) {
            // Dwell keyframes first, then newest: the newest 3 of each kind cover any backlog size (review I4).
            val picked = candidates(rows.map { it.frameId }, rows.associate { it.frameId to it.updatedAt })
                .sortedWith(compareByDescending<KeyframeCandidate> { it.dwell }.thenByDescending { it.ts }).take(MAX_BATCH)
            if (picked.isNotEmpty()) return runBatch(picked, callClass, user = false) { ids, claimId -> states.claim(ids, claimId, now, abandonBefore).map { it.frameId } }
        }
        clusterStep(now, abandonBefore)?.let { return it }
        if (captionsOk) captionStep(states.captionQueued(MAX_BATCH), now)?.let { return it }
        return Step.Idle(idleWait(now, abandonBefore, routeRetry))
    }

    /** Whether anything could be claimed or routed once the gate opens (a blocked loop only polls when there is something to do). */
    private suspend fun anythingWaiting(now: Long, abandonBefore: Long): Boolean =
        states.newestClaimable(now, abandonBefore, Long.MIN_VALUE, 0, 1).isNotEmpty() ||
            (clusterExtractor != null && clusters.claimable(now, abandonBefore, 1).isNotEmpty()) ||
            states.captionQueued(1).isNotEmpty() || (indexer != null && db.frameMetaDao().unroutedCount() > 0)

    /** Closes an aged cluster and routes what is waiting (up to [ROUTE_PASSES] batches of 8). Returns when to come back if the router held frames back. */
    private suspend fun route(force: Boolean): Long? {
        val ix = indexer ?: return null
        return try {
            ix.closeAged()
            var retry: Long? = null
            for (i in 0 until ROUTE_PASSES) {
                val r = ix.routeBatch(force)
                retry = r.retryInMs
                if (r.routed < SceneIndexer.BATCH) break
            }
            retry
        } catch (c: CancellationException) { throw c } catch (e: Exception) {
            Log.w(TAG, "routing failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun idleWait(now: Long, abandonBefore: Long, routeRetry: Long?): Long? {
        val waits = mutableListOf<Long>()
        states.nextRetryAt()?.let { waits += it - now }
        routeRetry?.let { waits += it }
        if (clusterExtractor != null) {
            clusters.nextRetryAt()?.let { waits += it - now }
            clusters.claimable(now, abandonBefore, 1).firstOrNull()?.let { waits += (it.closedAt ?: it.updatedAt) + CLUSTER_WAIT_MS - now }
        }
        if (indexer != null && clusters.open() != null) waits += OPEN_POLL_MS // an open cluster closes on age or gap: look again
        return waits.minOrNull()?.coerceAtLeast(1L)
    }

    /** Claims [picked] (through [claim]) and runs one extraction call over them. */
    private suspend fun runBatch(picked: List<KeyframeCandidate>, callClass: CallClass, user: Boolean, claim: suspend (List<String>, String) -> List<String>): Step {
        if (!user && power?.tryExtraction() == false) return Step.Idle(pollMs, blocked = true)
        val claimId = newClaimId()
        val claimed = claim(picked.map { it.frameId }, claimId).toSet()
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

    /**
     * Caption queue (spec §Captions, Ruling 9): the oldest [rows] go through the ordinary VISION_EXTRACT path as CAPTION_QUEUE calls, or as
     * EXTRACT once the oldest has waited 30 min. [rows] come oldest first. Null when nothing could be claimed.
     */
    private suspend fun captionStep(rows: List<FrameStateEntity>, now: Long): Step? {
        if (rows.isEmpty()) return null
        val cls = if (now - rows.first().updatedAt >= CAPTION_EXTRACT_AGE_MS) CallClass.EXTRACT else CallClass.CAPTION_QUEUE
        val chosen = rows.filter { cls == CallClass.CAPTION_QUEUE || now - it.updatedAt >= CAPTION_EXTRACT_AGE_MS }.take(MAX_BATCH)
        val picked = candidates(chosen.map { it.frameId }, chosen.associate { it.frameId to it.updatedAt })
        return runBatch(picked, cls, user = false) { ids, claimId -> states.claimCaptionQueued(ids, claimId, now).map { it.frameId } }
            .takeUnless { it is Step.Idle && it.waitMs == CONTENTION_MS }
    }

    /**
     * One cluster call (Ruling 5): at least [ClusterContract.MAX_CLUSTERS] claimable clusters, or the oldest has waited 60 s. Claims
     * up to 3, sends 3 representatives each (2 each for 3 clusters: at most 6 photos) and saves with the cluster CAS.
     */
    private suspend fun clusterStep(now: Long, abandonBefore: Long): Step? {
        val run = clusterExtractor ?: return null
        val ready = clusters.claimable(now, abandonBefore, ClusterContract.MAX_CLUSTERS)
        if (ready.isEmpty()) return null
        if (ready.size < ClusterContract.MAX_CLUSTERS && now - (ready.first().closedAt ?: ready.first().updatedAt) < CLUSTER_WAIT_MS) return null
        if (power?.tryExtraction() == false) return Step.Idle(pollMs, blocked = true)
        val claimId = newClaimId()
        val claimed = clusters.claim(ready.map { it.clusterId }, claimId, now, abandonBefore)
        sweepFinalClusters(now) // a claim found abandoned for the fifth time went to FAILED_FINAL in SQL
        if (claimed.isEmpty()) return Step.Idle(CONTENTION_MS)
        val live = HashMap<String, List<String>>()   // cluster id -> representatives still IN_CLUSTER, medoid first
        for (c in claimed) {
            val reps = inCluster(repIdsOf(c)).ifEmpty { inCluster(db.frameMetaDao().members(c.clusterId).map { it.frameId }) }
            if (reps.isEmpty()) failCluster(c.clusterId, claimId, "no_members", now, 1) else live[c.clusterId] = reps
        }
        if (live.isEmpty()) return Step.Done(0)
        val per = if (live.size >= ClusterContract.MAX_CLUSTERS) ClusterContract.MAX_PHOTOS / ClusterContract.MAX_CLUSTERS else PHOTOS_PER_CLUSTER
        val reps = live.mapValues { it.value.take(per) }
        val sent = claimed.filter { it.clusterId in reps }
        Log.i(PIPE_TAG, "clusters=${sent.size} photos=${reps.values.sumOf { it.size }}")
        val outcome = try {
            run(sent, reps, claimId, CallClass.EXTRACT)
        } catch (c: CancellationException) {
            withContext(NonCancellable) { sent.forEach { clusters.release(it.clusterId, claimId, clock()) } }
            Log.i(PIPE_TAG, "released=${sent.size} clusters cancelled")
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "cluster call failed: ${e.javaClass.simpleName}")
            ExtractOutcome.classify(e, online()).let { ClusterOutcome.Failed(it.error, it.countsAttempt, it.maxAttempts) }
        }
        return Step.Done(settleClusters(sent, claimId, outcome))
    }

    private fun repIdsOf(c: FrameClusterEntity): List<String> =
        try { JSONArray(c.repFrameIds).let { a -> List(a.length()) { a.getString(it) } } } catch (_: Exception) { emptyList() }

    /** [ids] (order kept) that are still IN_CLUSTER. */
    private suspend fun inCluster(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val st = ids.chunked(CHUNK).flatMap { states.byIds(it) }.filter { it.state == FrameState.IN_CLUSTER.name }.map { it.frameId }.toSet()
        return ids.filter { it in st }
    }

    private suspend fun failCluster(id: String, claimId: String, error: String, now: Long, maxAttempts: Int) {
        if (clusters.fail(id, claimId, error, now, maxAttempts) > 0) splitIfFinal(id, now)
    }

    /** Every FAILED_FINAL cluster that still has IN_CLUSTER members gets the Ruling-10 split (abandoned claims fail in SQL, with no caller). */
    private suspend fun sweepFinalClusters(now: Long) {
        for (id in clusters.finalWithOpenMembers()) splitIfFinal(id, now)
    }

    /** The one place a FAILED_FINAL cluster is settled. Ruling 10: a cluster at FAILED_FINAL: its representatives become FAILED_FINAL (describe-before-delete), its other members are skipped. */
    private suspend fun splitIfFinal(id: String, now: Long) {
        val c = clusters.get(id)?.takeIf { it.state == "FAILED_FINAL" } ?: return
        repIdsOf(c).chunked(CHUNK).forEach { states.move(it, FrameState.IN_CLUSTER.name, FrameState.FAILED_FINAL.name, "cluster_failed", now) }
        states.settleCluster(id, FrameState.NEAR_DUP.name, "cluster_failed", now)
        Log.i(PIPE_TAG, "cluster failed for good")
    }

    private suspend fun settleClusters(sent: List<FrameClusterEntity>, claimId: String, o: ClusterOutcome): Int {
        val now = clock()
        return when (o) {
            is ClusterOutcome.Saved -> {
                pauseStepMs = 0L; pausedUntilMs = 0L
                o.leftOut.forEach { failCluster(it, claimId, "cluster_invalid", now, FrameStateDao.MAX_ATTEMPTS) }
                o.gone.forEach { failCluster(it, claimId, "reps_gone", now, 1) }
                val done = sent.count { it.clusterId !in o.leftOut && it.clusterId !in o.gone && it.clusterId !in o.failedFinal }
                Log.i(PIPE_TAG, "summarised=$done leftOut=${o.leftOut.size} gone=${o.gone.size}")
                done
            }
            ClusterOutcome.Stale -> {
                withContext(NonCancellable) { sent.forEach { clusters.release(it.clusterId, claimId, clock()) } }
                Log.i(PIPE_TAG, "released=${sent.size} clusters stale")
                0
            }
            is ClusterOutcome.Failed -> {
                if (o.countsAttempt) {
                    sent.forEach { failCluster(it.clusterId, claimId, o.error, now, o.maxAttempts) }
                    Log.i(PIPE_TAG, "failed class=${o.error} clusters=${sent.size}")
                } else {
                    sent.forEach { clusters.release(it.clusterId, claimId, now) }
                    Log.i(PIPE_TAG, "released=${sent.size} clusters reason=${o.error}")
                    pauseStepMs = if (pauseStepMs == 0L) PAUSE_START_MS else minOf(pauseStepMs * 2, PAUSE_MAX_MS)
                    pausedUntilMs = now + pauseStepMs
                }
                0
            }
        }
    }

    /** Fix wave K5: the AI budget describes about 15 photos a minute; a bigger backlog is thinned (see [FrameStateDao.shedOldestNonDwell]). */
    private suspend fun shedBacklog(now: Long) {
        if (states.pendingCount() <= SHED_ABOVE) return
        val n = states.shedOldestNonDwell(SHED_KEEP_NEWEST, now)
        if (n > 0) Log.i(PIPE_TAG, "shed=$n")
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
        /** Above this many PENDING frames the oldest non-dwell ones beyond the newest [SHED_KEEP_NEWEST] are skipped (fix wave K5). */
        const val SHED_ABOVE = 40
        const val SHED_KEEP_NEWEST = 20
        const val ASK_WINDOW_MS = 60_000L
        /** An ask waits this long for frames the loop holds (the caller's own cap is 30 s as well). */
        const val ASK_WAIT_MS = 30_000L
        private const val ASK_POLL_MS = 100L
        private const val CHUNK = 500
        private const val CONTENTION_MS = 1_000L
        private const val ROUTE_PASSES = 5
        /** A closed cluster waits this long for companions before it is called alone. */
        const val CLUSTER_WAIT_MS = 60_000L
        private const val PHOTOS_PER_CLUSTER = 3
        /** Caption queue aging (Ruling 9): ahead of non-dwell PENDING after 10 min, an ordinary EXTRACT call after 30 min. */
        const val CAPTION_AGE_MS = 10 * 60_000L
        const val CAPTION_EXTRACT_AGE_MS = 30 * 60_000L
        /** While a cluster is open the loop looks again this often so it closes on age or gap. */
        private const val OPEN_POLL_MS = 30_000L
        private const val PAUSE_START_MS = 30_000L
        private const val PAUSE_MAX_MS = 5 * 60_000L
        private const val BACKOFF_MAX_MS = 30 * 60_000L

        /** A FAILED frame is retried after 30 s, 60 s, 2 min, 4 min ... (today's job schedule, capped at 30 min); the last attempt is final. Mirrors FrameStateDao's SQL. */
        fun failureBackoffMs(attempts: Int): Long = minOf(30_000L shl (attempts - 1).coerceIn(0, 20), BACKOFF_MAX_MS)
    }
}
