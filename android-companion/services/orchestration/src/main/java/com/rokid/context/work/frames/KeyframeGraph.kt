package com.rokid.context.work.frames

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.Skipped
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ClusterContract
import com.rokid.context.ai.contract.ClusterPhoto
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.FrameIntentSink
import com.rokid.context.canvas.LiveDeps
import com.rokid.context.canvas.RunContext
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.ExtractFollowUp
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.SavedMemory
import com.rokid.context.orchestration.nudge.NudgeDecision
import com.rokid.context.orchestration.nudge.NudgeEvaluator
import com.rokid.context.orchestration.nudge.NudgeRun
import com.rokid.context.orchestration.nudge.NudgeEngineFactory
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.budget.BudgetStats
import com.rokid.context.work.budget.CallBudget
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.budget.ThermalStatusProvider
import com.rokid.context.work.frames.scene.ClusterReview
import com.rokid.context.work.frames.scene.ClusterSave
import com.rokid.context.work.frames.scene.ClusterSaveResult
import com.rokid.context.work.frames.scene.MlKitTextPresence
import com.rokid.context.work.frames.scene.SceneIndexer
import com.rokid.context.orchestration.PhotoRead
import com.rokid.context.work.frames.scene.SpaceImageSpaces
import com.rokid.context.work.frames.scene.WatchListStore
import com.rokid.context.work.frames.scene.WatchVectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * The process-wide keyframe pipeline (spec §P2): one [KeyframePipeline] with its power policy, stats and fast-path nudge
 * evaluator. Built lazily; the hub service, the app start and the 15-minute workers all go through [get] and [KeyframeHandle.start]
 * (idempotent), so frames are processed whether or not the hub service is running.
 */
object KeyframeGraph {
    /** The phone's thermal status (the app installs `PowerManager.currentThermalStatus`); NONE until then. */
    @Volatile var thermal: ThermalStatusProvider = ThermalStatusProvider { PhoneThermal.NONE }
    /** Whether the app is in the foreground (the app installs ProcessLifecycleOwner). */
    @Volatile var foreground: () -> Boolean = { true }

    /**
     * Wave B: the image-intent sampler, installed by the `image_intent` context source while its switch is on and nulled when it
     * stops. [KeyframeHandle.signal] pokes it (a failure there never reaches the pipeline).
     */
    @Volatile var intentSampler: com.rokid.context.work.frames.intent.IntentSampler? = null
    /** Place Guide: the matcher the hub installs; poked after each committed frame like [intentSampler] (it does nothing unless the switch is on and the frame is a dwell photo). */
    @Volatile var placeSampler: com.rokid.context.work.frames.place.PlaceSampler? = null

    /**
     * Test override of the canvas intent sink. [FrameIntentSink.None] (the default) means the production writer: [com.rokid.context.work.frames.intent.FrameIntentWriter]
     * over the same [com.rokid.context.work.frames.intent.RoomIntentFrameStore] and tombstone/row fences as the image-intent sampler.
     */
    @Volatile var intentSink: FrameIntentSink = FrameIntentSink.None

    @Volatile private var handle: KeyframeHandle? = null

    init { MemoryGraph.onTestReset { resetForTests() } } // the handle's pipeline captures the graph's database (tests only)

    fun get(context: Context): KeyframeHandle = handle ?: synchronized(this) { handle ?: KeyframeHandle(context.applicationContext).also { handle = it } }

    /**
     * Whether the scene indexer is active right now (an IMAGE-capable embedder is bound and the phone may make image vectors, Ruling 2):
     * the ingest then leaves `routedAt` NULL so the router, not the per-photo path, sees the frame first. False on any failure
     * (wave-1 behaviour: the frame is claimable at once).
     */
    suspend fun sceneActive(context: Context): Boolean = try { get(context).sceneActive() } catch (c: CancellationException) { throw c } catch (_: Exception) { false }

    fun resetForTests() { synchronized(this) { handle?.stopIfBuilt(); handle = null } } // never BUILDS a pipeline just to stop it

    /** [PowerManager.currentThermalStatus] as a [ThermalStatusProvider] (API 29+; NONE below). */
    fun systemThermal(context: Context): ThermalStatusProvider = ThermalStatusProvider {
        val s = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: 0
        PhoneThermal.entries.getOrElse(s) { if (s > 0) PhoneThermal.SHUTDOWN else PhoneThermal.NONE }
    }
}

class KeyframeHandle internal constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis
    private val db get() = MemoryGraph.get(app).db
    private val offerer by lazy { NudgeOfferer { id, eligible, captureMs -> nudges.offer(id, eligible, captureMs) } }

    val power = PhonePowerPolicy(clock, { KeyframeGraph.foreground() }, ThermalStatusProvider { KeyframeGraph.thermal.status() })

    /** Fast-path nudges (spec §P2.1): [KeyframePipeline] offers every extracted memory; timing comes from the Nudges settings. */
    val nudges: NudgeEvaluator by lazy {
        val settings = SourcesGraph.get(app).settings
        NudgeEvaluator(clock, { settings.settleSec.value * 1000L }, { settings.checkGapSec.value * 1000L }, { evaluateNudge() }, scope)
    }

    private val wakeLock by lazy { ExtractWakeLock(app) }

    private val imageSpaces by lazy { SpaceImageSpaces(MemoryGraph.get(app).spaces, AiGraph.get(app).embedders) }
    @Volatile private var sceneCheckedAt = 0L
    @Volatile private var sceneCached = false

    /** [KeyframeGraph.sceneActive], cached for 30 s (the check builds the space's embedder). */
    internal suspend fun sceneActive(): Boolean {
        val now = clock()
        if (now - sceneCheckedAt < SCENE_CHECK_MS) return sceneCached
        val active = power.mayRunBackground(BackgroundWork.IMAGE_VECTORS) && imageSpaces.targets().any { it.active }
        sceneCached = active; sceneCheckedAt = now
        return active
    }

    /** Scene routing (spec P4.1). With no IMAGE-capable embedder every target list is empty and frames stay on the wave-1 per-photo path. */
    val indexer: SceneIndexer by lazy {
        SceneIndexer(db, { imageSpaces.targets() }, MlKitTextPresence(), WatchVectors(WatchListStore.get(app)), power, ::photoBytes, clock, foreground = { KeyframeGraph.foreground() })
    }

    private val pipelineLazy = lazy {
        KeyframePipeline(
            db, { ids, claim, cls -> wakeLock.hold { extract(ids, claim, cls) } }, clock, power, deferred = ::batteryDeferral, online = ::networkValidated,
            onExtracted = ::afterExtraction, releaseAtStart = true, indexer = indexer, dataSaverBlocked = { dataGate.refresh(); dataSaverHeldBack() },
            dataSaverBlockedSince = { (dataGate as? com.rokid.context.ai.datasaver.DataSaverGraph)?.blockedSince() ?: 0L },
            dataSaverHandled = { since -> (dataGate as? com.rokid.context.ai.datasaver.DataSaverGraph)?.clearBlocked(since) }, clusterExtractor = { cl, reps, claim, cls -> wakeLock.hold { summarize(cl, reps, claim, cls) } },
        )
    }

    val pipeline: KeyframePipeline by pipelineLazy
    internal fun stopIfBuilt() { if (pipelineLazy.isInitialized()) pipeline.stop() }

    /** Describe on demand (wave 3): the user's own VISION_EXTRACT over chosen photos or a scene; the claim path is [KeyframePipeline.extractNow]. */
    val describe: com.rokid.context.work.frames.recall.DescribeOnDemand by lazy {
        com.rokid.context.work.frames.recall.DescribeOnDemand(app, pipeline, db, clock, online = ::networkValidated, activeSpaceId = { imageSpaces.targets().firstOrNull { it.active }?.spaceId })
    }

    /** The JPEG bytes of [ids] (decrypted when sealed); a frame whose file is gone is left out. */
    internal suspend fun photoBytes(ids: List<String>): Map<String, ByteArray> =
        PipelineRecords.framesByIds(app, ids).mapNotNull { f -> FrameImages.readBytes(f.path)?.let { f.id to it } }.toMap()

    /**
     * [photoBytes] for the backfill, with the reason a photo is missing: a frame row or file that is gone is [PhotoRead.Missing]; a file that is
     * there but cannot be read or unsealed just now (I/O, Keystore) is [PhotoRead.Unreadable], which is retried later and never final at once.
     */
    internal suspend fun photoReads(ids: List<String>): Map<String, PhotoRead> {
        val frames = PipelineRecords.framesByIds(app, ids).associateBy { it.id }
        return ids.associateWith { id -> frames[id]?.let { f -> FrameImages.readBytes(f.path)?.let { PhotoRead.Bytes(it) } ?: PhotoRead.Unreadable } ?: PhotoRead.Missing }
    }

    val stats: StateFlow<KeyframeStats> by lazy {
        val budget = (AiGraph.gate as? CallBudget)?.stats ?: MutableStateFlow(BudgetStats())
        KeyframeStatsReporter.create(scope, db, budget, pipeline.latency, clock)
    }

    private var networkWatch: ConnectivityManager.NetworkCallback? = null
    private val openHooked = AtomicBoolean(false)
    /** The roaming data saver (the app's one [com.rokid.context.ai.datasaver.DataSaverGraph]). */
    internal val dataGate: DataGate by lazy { com.rokid.context.ai.datasaver.DataSaverGraph.get(app).also { it.reachProvider = ::photoReach } }
    private val started = AtomicBoolean(false)

    /** Starts the loop, requeues abandoned claims (startup recovery) and watches for the network coming back. Idempotent: recovery runs once. */
    fun start() {
        pipeline.start(scope)
        if (!started.compareAndSet(false, true)) return
        scope.launch { runCatching { pipeline.recover() }.onFailure { PcLog.w(TAG, "recovery failed: ${it.javaClass.simpleName}") } }
        watchNetwork()
    }

    /** A frame committed to Room (the drain, or the hub after storing a receipt). Starts the loop if needed but never scans (review M1). */
    fun signal() {
        pipeline.start(scope); pipeline.signal()
        try { KeyframeGraph.intentSampler?.poke() } catch (e: Exception) { PcLog.w(TAG, "intent poke failed: ${e.javaClass.simpleName}") }
        try { KeyframeGraph.placeSampler?.poke() } catch (e: Exception) { PcLog.w(TAG, "place poke failed: ${e.javaClass.simpleName}") }
    }

    /**
     * Admission for optional background model calls that share the pipeline's gates (image intent): not under Battery Saver or a low
     * battery, a validated network, and the phone's power/thermal policy (SEVERE pauses; at MODERATE it takes the shared
     * one-per-30-s extraction allowance, so intent and extraction cannot together exceed it).
     */
    fun admitBackgroundCall(): Boolean = !batteryDeferral() && networkValidated() && power.tryExtraction()

    /**
     * [admitBackgroundCall] plus the roaming data saver, for the optional photo calls (image intent, Place Guide PLACE_MATCH). They are dropped, never queued: a card for where the user
     * stood an hour ago is useless. The data check comes first so a refused call neither takes the shared extraction allowance nor reaches the model. A refusal is counted (a count and a reason
     * token in the PcData line, no content).
     */
    fun admitPhotoCall(): Boolean = dataSaverAllowsPhoto() && admitBackgroundCall()

    /** The data-saver part alone: false (and one counted, logged refusal) when a photo may not go out on this network. Place Guide uses it separately so it can still show the offline-style card. */
    fun dataSaverAllowsPhoto(): Boolean {
        val d = dataGate.decide(DataKind.PHOTO, photoReach())
        if (!d.allowed) { dataGate.skipped(DataKind.PHOTO, Skipped.DROPPED, d); return false }
        return true
    }

    /** Photo uploads are blocked right now (policy only; no count, no log): the Ask photo gate and tests read it. */
    /** The debounced, reach-aware "held back" decision (see [com.rokid.context.ai.datasaver.DataSaverGraph.heldBack]): what the drain parks on. Falls back to the plain decision for a foreign gate. */
    internal fun dataSaverHeldBack(): Boolean = (dataGate as? com.rokid.context.ai.datasaver.DataSaverGraph)?.heldBack() ?: !photoUploadsAllowed()

    fun photoUploadsAllowed(): Boolean = dataGate.decide(DataKind.PHOTO, photoReach()).allowed

    @Volatile private var reachAt = 0L
    @Volatile private var reachCached = com.rokid.context.core.net.EndpointReach.REMOTE

    /**
     * Where the photo calls go: the VISION_EXTRACT binding's endpoint, classified like the router does (on this phone or on the LAN is not a data cost). Cached for a minute (a database read);
     * unknown or unreadable is REMOTE, the fail-closed answer. The same endpoint serves describe, place match and intent in the default setup.
     */
    internal fun photoReach(): com.rokid.context.core.net.EndpointReach {
        val now = clock()
        if (now - reachAt < REACH_TTL_MS) return reachCached
        // Never block the main thread (a Room read, and AiGraph may not exist yet): answer from the cache (REMOTE, fail-closed, until warmed) and warm it off-main.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (reachWarming.compareAndSet(false, true)) scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { storeReach(readReach()) } finally { reachWarming.set(false) } }
            return reachCached
        }
        storeReach(readReach())
        return reachCached
    }

    private val reachWarming = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun storeReach(r: com.rokid.context.core.net.EndpointReach) { reachCached = r; reachAt = clock() }
    private fun readReach(): com.rokid.context.core.net.EndpointReach = try {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val ai = AiGraph.get(app)
            // The router tries the primary and then the fallback, so the call can go out wherever the best of the two reaches (a remote primary with an on-device or LAN fallback is not "remote" under Offline only).
            ai.bindings.get(com.rokid.context.ai.Role.VISION_EXTRACT)?.let { b ->
                com.rokid.context.ai.EndpointReaches.photoReach(listOfNotNull(b.endpointId, b.fallbackEndpointId).mapNotNull { ai.endpoints.byId(it) }) // only endpoints that can take the photo count
            }
        } ?: com.rokid.context.core.net.EndpointReach.REMOTE
    } catch (e: Exception) { com.rokid.context.core.net.EndpointReach.REMOTE }

    @Synchronized private fun watchNetwork() {
        if (networkWatch != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { runCatching { pipeline.recover() } }
                pipeline.onNetworkReturn()
            }
        }
        // The roaming data saver opening (Wi-Fi back, mode or abroad switch changed) wakes the parked loop like a returning network does.
        if (openHooked.compareAndSet(false, true)) (dataGate as? com.rokid.context.ai.datasaver.DataSaverGraph)?.addOpenListener { scope.launch { runCatching { pipeline.parkForDataSaver() }; pipeline.onNetworkReturn() } }
        try { cm.registerDefaultNetworkCallback(cb); networkWatch = cb } catch (e: Exception) { PcLog.w(TAG, "network watch unavailable: ${e.javaClass.simpleName}") }
    }

    /** Whether the active network has validated internet access (ConnectivityManager). Unknown counts as online: only a clear "no" means offline. */
    internal fun networkUp(): Boolean = networkValidated()

    private fun networkValidated(): Boolean = networkState() != com.rokid.context.core.net.NetState.NONE

    /**
     * The same reading as [networkUp], but the "the check itself failed" case is told apart: [com.rokid.context.core.net.NetState.UNKNOWN] (no ConnectivityManager, or an exception) versus [com.rokid.context.core.net.NetState.NONE]
     * (no validated network). [networkUp] keeps treating UNKNOWN as up (today's behaviour in AUTO); the model resolver never lets UNKNOWN open a remote call under Offline only.
     */
    internal fun networkState(): com.rokid.context.core.net.NetState = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
        if (cm == null) com.rokid.context.core.net.NetState.UNKNOWN
        else if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) com.rokid.context.core.net.NetState.VALIDATED
        else com.rokid.context.core.net.NetState.NONE
    } catch (e: Exception) { com.rokid.context.core.net.NetState.UNKNOWN }

    /** Battery saver, or battery <= 30% and not charging: today's deferral rules (spec §P2.5). */
    private fun batteryDeferral(): Boolean {
        if ((app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true) return true
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return level >= 0 && level * 100 / scale.coerceAtLeast(1) <= 30 && !charging
    }

    /** One VISION_EXTRACT over [frameIds] (<= 3, labelled with their ids), saved with the claim CAS inside the transaction. */
    private suspend fun extract(frameIds: List<String>, claimId: String, callClass: CallClass): ExtractOutcome =
        KeyframeExtract.run(app, frameIds, claimId, callClass, factPrefix(claimId), ::networkValidated, nudge = callClass != CallClass.BACKFILL && kotlin.coroutines.coroutineContext[NoNudge.Key] == null,
            quiet = kotlin.coroutines.coroutineContext[NoNudge.Key] != null, live = liveDeps)

    private val intentWriter: FrameIntentSink by lazy {
        com.rokid.context.work.frames.intent.FrameIntentWriter(com.rokid.context.work.frames.intent.RoomIntentFrameStore { db })
    }

    /** The canvas interpreter seam's dependencies (spec section 5): no interpreter publish means the plain extraction. Built on first use. */
    private val liveDeps: LiveDeps by lazy {
        val ai = AiGraph.get(app)
        val dao = db.canvasDao()
        LiveDeps(
            dao = dao, runner = ai.runner,
            context = { runId, callClass, liveness -> RunContext(runId, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, callClass = callClass, liveness = liveness) },
            sink = FrameIntentSink { frameId, label, confidence, atMs -> (KeyframeGraph.intentSink.takeIf { it !== FrameIntentSink.None } ?: intentWriter).record(frameId, label, confidence, atMs) },
            publisher = { id, reason -> CanvasPublisher.autoRollbackOn(dao, clock, id, reason) },
            shadowAllowed = { app.getSharedPreferences("pc_dev", Context.MODE_PRIVATE).getBoolean("developer_mode", false) },
            clock = clock, scope = scope,
        )
    }

    /**
     * One CLUSTER_SUMMARY call over the representatives [reps] of [clusters] (wave 2). The cluster CAS is inside [ClusterSave]; the follow-up
     * (embeddings) runs after it. [ClusterContract.call] leaves out a cluster that stays invalid alone: it is reported as left out so the
     * pipeline fails it with an attempt. Cluster memories are not offered to the fast-path nudge (Ruling 16); the backstop sees them.
     */
    private suspend fun summarize(clusters: List<FrameClusterEntity>, reps: Map<String, List<String>>, claimId: String, callClass: CallClass): ClusterOutcome {
        val generation = com.rokid.context.orchestration.MemoryWipe.generation.get()
        val loaded = FramePhotos.load(app, reps.values.flatten().distinct())
        val frames = PipelineRecords.framesByIds(app, loaded.photos.map { it.frameId }).associateBy { it.id }
        val motion = clusters.associate { it.clusterId to (it.motionClass ?: "UNKNOWN") }
        val photos = reps.flatMap { (cid, ids) ->
            ids.mapNotNull { id ->
                val img = loaded.photos.firstOrNull { it.frameId == id } ?: return@mapNotNull null
                val ts = frames[id]?.ts ?: 0L
                ClusterPhoto(cid, id, android.util.Base64.decode(img.image.base64Data, android.util.Base64.DEFAULT), "cluster_id: $cid frame_id: $id time: ${java.time.Instant.ofEpochMilli(ts)} motion: ${motion[cid]}")
            }
        }
        val withPhotos = photos.map { it.clusterId }.toSet()
        val gone = reps.filter { (cid, ids) -> cid !in withPhotos && ids.all { it in loaded.gone } }.keys
        val unseen = reps.keys - withPhotos - gone
        if (photos.isEmpty()) return ClusterOutcome.Saved(emptyList(), leftOut = unseen, gone = gone)
        // Codex r10: the photos carry their refs and this liveness; the router re-checks them inside every call's permit, under the upload slot.
        val out = try {
            ClusterContract.call(AiGraph.get(app).runner, photos, callClass, FrameLiveness.forApp(app, generation))
        } catch (f: com.rokid.context.ai.PhotoForgottenException) {
            return ClusterOutcome.Stale // dropped unsent; nothing written, never retried as text
        }
        val sent = out.clusters.associate { c -> c.clusterId to photos.filter { it.clusterId == c.clusterId }.map { it.frameId } }
        val leftOut = unseen + (withPhotos - sent.keys)
        // Wave 1's free local critic (no AI call) decides the facts' review state; no model critic per cluster (cost). An unsure
        // verdict saves the facts NEEDS_REVIEW and afterSave(trusted = false) queues the PRIORITY_REPROCESS wave 1 uses.
        val verdict = ClusterReview.of(out, claimId)
        return when (val saved = ClusterSave.save(app, claimId, out, sent, generation, clock(), verdict.factReview)) {
            is ClusterSaveResult.Saved -> {
                withContext(NonCancellable) {
                    ExtractFollowUp.afterSave(app, generation, saved.memories, verdict.trusted, verdict.confidence, AppConfigStore.getWithoutSecret(app).persona, nudge = callClass != CallClass.BACKFILL)
                }
                ClusterOutcome.Saved(saved.memories, saved.failedClusters.toSet(), leftOut, gone)
            }
            ClusterSaveResult.Stale, ClusterSaveResult.Wiped -> ClusterOutcome.Stale
            // no delivery proof: nothing written; thrown as a retryable failure so the clusters stay queued with backoff
            ClusterSaveResult.Undelivered -> throw com.rokid.context.ai.ModelCallException("photos not delivered", retryable = true)
        }
    }

    /** After a save (embeddings and the nudge backstop were done by [ExtractFollowUp]): an offer to the nudge evaluator for memories from dwell keyframes or a new place. */
    private suspend fun afterExtraction(frames: List<KeyframeCandidate>, memories: List<SavedMemory>) {
        if (memories.isEmpty()) return
        val places = PipelineRecords.framesByIds(app, frames.map { it.frameId }).associate { it.id to it.placeLabel }
        offerer.onExtracted(frames, memories, places)
    }

    /** Live nudges pause under Battery Saver and at thermal SEVERE (spec §P2.5), for asks' extractions too. */
    internal fun nudgesPaused(): Boolean = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true || power.severe()

    /** What the periodic backstop does today: one engine pass (its gates apply to showing only). */
    internal suspend fun evaluateNudge(): NudgeDecision {
        if (nudgesPaused()) return NudgeDecision(shouldNudge = false, reason = "Paused")
        val run = NudgeEngineFactory.engine(app).runOnce()
        return NudgeDecision(shouldNudge = run is NudgeRun.Shown, reason = run.javaClass.simpleName)
    }

    internal companion object {
        private const val TAG = "KeyframeGraph"
        private const val SCENE_CHECK_MS = 30_000L
        private const val REACH_TTL_MS = 60_000L

        /** The memory id prefix of one extraction: the WHOLE claim id (a 12-char prefix collided, and a collision replaced another batch's memories). */
        fun factPrefix(claimId: String) = "$ID_ROOT${claimId}_"

        /**
         * Must start with `rokid_` (PipelineRecords.purgeNonRokidMemories deletes everything else on every launch) and must not start
         * with `rokid_review_`. Builds before the final review saved `kf_<claimId>_<n>`; the purges still treat [LEGACY_ID_ROOT] as theirs.
         */
        const val ID_ROOT = "rokid_kf_"
        const val LEGACY_ID_ROOT = "kf_"
    }
}
