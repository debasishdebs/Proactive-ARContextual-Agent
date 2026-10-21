package com.rokid.context.work.frames

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ClusterContract
import com.rokid.context.ai.contract.ClusterPhoto
import com.rokid.context.ai.contract.ExtractOutput
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

    @Volatile private var handle: KeyframeHandle? = null

    fun get(context: Context): KeyframeHandle = handle ?: synchronized(this) { handle ?: KeyframeHandle(context.applicationContext).also { handle = it } }

    /**
     * Whether the scene indexer is active right now (an IMAGE-capable embedder is bound and the phone may make image vectors, Ruling 2):
     * the ingest then leaves `routedAt` NULL so the router, not the per-photo path, sees the frame first. False on any failure
     * (wave-1 behaviour: the frame is claimable at once).
     */
    suspend fun sceneActive(context: Context): Boolean = try { get(context).sceneActive() } catch (c: CancellationException) { throw c } catch (_: Exception) { false }

    fun resetForTests() { synchronized(this) { handle?.pipeline?.stop(); handle = null } }

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

    val pipeline: KeyframePipeline by lazy {
        KeyframePipeline(
            db, { ids, claim, cls -> wakeLock.hold { extract(ids, claim, cls) } }, clock, power, deferred = ::batteryDeferral, online = ::networkValidated,
            onExtracted = ::afterExtraction, releaseAtStart = true, indexer = indexer, clusterExtractor = { cl, reps, claim, cls -> wakeLock.hold { summarize(cl, reps, claim, cls) } },
        )
    }

    /** The JPEG bytes of [ids] (decrypted when sealed); a frame whose file is gone is left out. */
    internal suspend fun photoBytes(ids: List<String>): Map<String, ByteArray> =
        PipelineRecords.framesByIds(app, ids).mapNotNull { f -> FrameImages.readBytes(f.path)?.let { f.id to it } }.toMap()

    val stats: StateFlow<KeyframeStats> by lazy {
        val budget = (AiGraph.gate as? CallBudget)?.stats ?: MutableStateFlow(BudgetStats())
        KeyframeStatsReporter.create(scope, db, budget, pipeline.latency, clock)
    }

    private var networkWatch: ConnectivityManager.NetworkCallback? = null
    private val started = AtomicBoolean(false)

    /** Starts the loop, requeues abandoned claims (startup recovery) and watches for the network coming back. Idempotent: recovery runs once. */
    fun start() {
        pipeline.start(scope)
        if (!started.compareAndSet(false, true)) return
        scope.launch { runCatching { pipeline.recover() }.onFailure { Log.w(TAG, "recovery failed: ${it.javaClass.simpleName}") } }
        watchNetwork()
    }

    /** A frame committed to Room (the drain, or the hub after storing a receipt). Starts the loop if needed but never scans (review M1). */
    fun signal() { pipeline.start(scope); pipeline.signal() }

    @Synchronized private fun watchNetwork() {
        if (networkWatch != null) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { runCatching { pipeline.recover() } }
                pipeline.onNetworkReturn()
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); networkWatch = cb } catch (e: Exception) { Log.w(TAG, "network watch unavailable: ${e.javaClass.simpleName}") }
    }

    /** Whether the active network has validated internet access (ConnectivityManager). Unknown counts as online: only a clear "no" means offline. */
    private fun networkValidated(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let(cm::getNetworkCapabilities)
        cm == null || (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    } catch (e: Exception) { true }

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
    private suspend fun extract(frameIds: List<String>, claimId: String, callClass: CallClass): ExtractOutcome {
        val generation = com.rokid.context.orchestration.MemoryWipe.generation.get()
        val loaded = FramePhotos.load(app, frameIds)
        val photos = loaded.photos
        if (photos.isEmpty()) return ExtractOutcome.Failed("unreadable", unreadable = loaded.gone, unseen = loaded.unseen)
        val ai = AiGraph.get(app)
        val batch = try {
            ExtractSave.extract(ai.runner, photos, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return ExtractOutcome.classify(e, online = networkValidated()).copy(unreadable = loaded.gone, unseen = loaded.unseen)
        }
        val facts = batch.output.facts.filter { it.text.isNotBlank() }
        val sent = photos.map { it.frameId }
        // The same trust verdict (local critic, then the model critic) and follow-up as the legacy job (review I6). Final review I2: the
        // paid extraction is SAVED first (NEEDS_REVIEW when the local critic is unsure); the model critic runs after, under the caller's
        // own CallClass, and only upgrades the memories. An ask's cap that lands during the critic therefore cannot lose the extraction.
        val sentFrames = PipelineRecords.framesByIds(app, sent)
        val critic = CriticWorker()
        val localTrusted = ExtractFollowUp.locallyTrusted(critic, claimId, facts)
        val saved = ExtractSave.saveExtraction(
            app, factPrefix(claimId), ExtractOutput(facts, captions = batch.output.captions, provenance = batch.output.provenance), sent.toSet(),
            claims = sent.associateWith { claimId }, tier = MemoryTier.SHORT, reviewState = if (localTrusted) ReviewState.OK else ReviewState.NEEDS_REVIEW,
            generation = generation,
        )
        return when (saved) {
            is ExtractSaveResult.Saved -> {
                var trusted = localTrusted
                try {
                    if (!localTrusted) {
                        trusted = ExtractFollowUp.modelCriticPasses(ai, facts, sentFrames.map(ExtractFollowUp::frameLine), callClass)
                        if (trusted) {
                            val view = MemoryGraph.get(app).db.memoryViewDao()
                            saved.memories.filter { it.index != null }.forEach { view.moveReviewState(it.memoryId, ReviewState.NEEDS_REVIEW.name, ReviewState.OK.name) }
                        }
                    }
                } finally {
                    // Embeddings, the nudge backstop (trusted) or a PRIORITY_REPROCESS job (low confidence), exactly as the legacy job did;
                    // also when the critic was cancelled (the memories are saved and the claim is done).
                    withContext(NonCancellable) {
                        ExtractFollowUp.afterSave(app, generation, saved.memories, trusted, ExtractFollowUp.confidence(facts), AppConfigStore.getWithoutSecret(app).persona)
                    }
                }
                ExtractOutcome.Saved(saved.memories, loaded.gone, loaded.unseen)
            }
            else -> ExtractOutcome.Stale(loaded.gone, loaded.unseen)
        }
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
        val out = ClusterContract.call(AiGraph.get(app).runner, photos, callClass)
        val sent = out.clusters.associate { c -> c.clusterId to photos.filter { it.clusterId == c.clusterId }.map { it.frameId } }
        val leftOut = unseen + (withPhotos - sent.keys)
        // Wave 1's free local critic (no AI call) decides the facts' review state; no model critic per cluster (cost). An unsure
        // verdict saves the facts NEEDS_REVIEW and afterSave(trusted = false) queues the PRIORITY_REPROCESS wave 1 uses.
        val verdict = ClusterReview.of(out, claimId)
        return when (val saved = ClusterSave.save(app, claimId, out, sent, generation, clock(), verdict.factReview)) {
            is ClusterSaveResult.Saved -> {
                withContext(NonCancellable) {
                    ExtractFollowUp.afterSave(app, generation, saved.memories, verdict.trusted, verdict.confidence, AppConfigStore.getWithoutSecret(app).persona)
                }
                ClusterOutcome.Saved(saved.memories, saved.failedClusters.toSet(), leftOut, gone)
            }
            ClusterSaveResult.Stale, ClusterSaveResult.Wiped -> ClusterOutcome.Stale
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
