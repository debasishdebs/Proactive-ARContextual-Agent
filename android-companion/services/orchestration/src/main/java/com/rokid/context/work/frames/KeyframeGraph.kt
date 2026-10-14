package com.rokid.context.work.frames

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.domain.summary.CriticWorker
import com.rokid.context.domain.summary.SummaryStatus
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.EmbeddingWorker
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.ExtractSave
import com.rokid.context.orchestration.ExtractSaveResult
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.SavedMemory
import com.rokid.context.orchestration.nudge.NudgeDecision
import com.rokid.context.orchestration.nudge.NudgeEvaluator
import com.rokid.context.orchestration.nudge.NudgeRun
import com.rokid.context.orchestration.nudge.NudgeEngineFactory
import com.rokid.context.work.budget.BudgetStats
import com.rokid.context.work.budget.CallBudget
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.budget.ThermalStatusProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    val pipeline: KeyframePipeline by lazy {
        KeyframePipeline(db, ::extract, clock, power, deferred = ::batteryDeferral, onExtracted = ::afterExtraction)
    }

    val stats: StateFlow<KeyframeStats> by lazy {
        val budget = (AiGraph.gate as? CallBudget)?.stats ?: MutableStateFlow(BudgetStats())
        KeyframeStatsReporter.create(scope, db, budget, pipeline.latency, clock)
    }

    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    /** Starts the loop, requeues abandoned claims (startup recovery) and watches for the network coming back. */
    fun start() {
        pipeline.start(scope)
        scope.launch { runCatching { pipeline.recover() }.onFailure { Log.w(TAG, "recovery failed: ${it.javaClass.simpleName}") } }
        watchNetwork()
    }

    /** A frame committed to Room (the drain, or the hub after storing a receipt). */
    fun signal() { start(); pipeline.signal() }

    private fun watchNetwork() {
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
        val frames = PipelineRecords.framesByIds(app, frameIds) // forgotten and missing files drop out
        val photos = frames.mapNotNull { f ->
            FrameInputs.fromFrames(listOf(f), maxImages = 1).singleOrNull()?.let { ExtractPhoto(f.id, "ts=${f.ts} · source=${f.source} · ${f.payload}", it) }
        }
        val unreadable = frameIds.toSet() - photos.map { it.frameId }.toSet()
        if (photos.isEmpty()) return ExtractOutcome.Failed("unreadable", unreadable = unreadable)
        val batch = try {
            ExtractSave.extract(AiGraph.get(app).runner, photos, callClass)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return ExtractOutcome.classify(e).copy(unreadable = unreadable)
        }
        val facts = batch.output.facts.filter { it.text.isNotBlank() }
        val confidence = if (facts.isEmpty()) 1.0 else facts.map { it.confidence }.average()
        val trusted = CriticWorker().validate(claimId, confidence).status == SummaryStatus.TRUSTED
        val sent = photos.map { it.frameId }
        val saved = ExtractSave.saveExtraction(
            app, "kf_${claimId.take(12)}_", ExtractOutput(facts, captions = batch.output.captions, provenance = true), sent.toSet(),
            claims = sent.associateWith { claimId }, tier = MemoryTier.SHORT, reviewState = if (trusted) ReviewState.OK else ReviewState.NEEDS_REVIEW,
            generation = generation,
        )
        return when (saved) {
            is ExtractSaveResult.Saved -> ExtractOutcome.Saved(saved.memories, unreadable)
            else -> ExtractOutcome.Stale(unreadable)
        }
    }

    /** After a save: embeddings, and an offer to the nudge evaluator for memories from dwell keyframes or a new place. */
    private suspend fun afterExtraction(frames: List<KeyframeCandidate>, memories: List<SavedMemory>) {
        if (memories.isEmpty()) return
        EmbeddingWorker.enqueue(app)
        val places = PipelineRecords.framesByIds(app, frames.map { it.frameId }).associate { it.id to it.placeLabel }
        offerer.onExtracted(frames, memories, places)
    }

    /** What the periodic NudgeWorker does today: one engine pass (its gates apply to showing only). */
    private suspend fun evaluateNudge(): NudgeDecision {
        val run = NudgeEngineFactory.engine(app).runOnce()
        return NudgeDecision(shouldNudge = run is NudgeRun.Shown, reason = run.javaClass.simpleName)
    }

    private companion object { const val TAG = "KeyframeGraph" }
}
