package com.rokid.context.phone.hub

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.link.CxrGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.actions.PhoneActions
import com.rokid.context.phone.nudge.PhoneNudges
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide hub (no DI in v1). `ContextApp` has already initialised [MemoryGraph]. */
object HubGraph {
    @Volatile private var hub: Hub? = null

    fun get(context: Context): Hub = hub ?: synchronized(this) { hub ?: build(context.applicationContext).also { hub = it } }

    /** Phase 3 → Phase 4 contract: the real glasses status for the phone UI. */
    fun glassesStatus(context: Context): GlassesStatusSource = get(context)

    internal val isBuilt: Boolean get() = hub != null

    /** Wave B: the one process-wide context hub and its registry; later tasks call `contextRegistry?.register(…)`. Built with the hub, started with it. */
    @Volatile var contextHub: com.rokid.context.phone.context.ContextHub? = null
        private set
    @Volatile var contextRegistry: com.rokid.context.phone.context.ContextSourceRegistry? = null
        private set

    fun resetForTests(h: Hub?) { synchronized(this) { hub = h } }

    /** Off the main thread: the receipt commit does Room and file I/O. The process-wide hub outlives it. */
    private val commitScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.w(Hub.TAG, "receipt commit scope failed: ${t.javaClass.simpleName}") },
    )

    /** Single-flight + dirty flag for the arrival drain: a burst of frames costs at most two full drains (final review N2). */
    internal class DrainCoalescer {
        private var running = false
        private var dirty = false

        /** Runs [drain] on [scope]; if one is already running it is marked dirty and loops once more instead of a second start. */
        fun request(scope: CoroutineScope, drain: suspend () -> Unit) {
            synchronized(this) { if (running) { dirty = true; return }; running = true }
            scope.launch {
                try {
                    while (true) {
                        synchronized(this@DrainCoalescer) { dirty = false }
                        drain()
                        synchronized(this@DrainCoalescer) { if (!dirty) { running = false; return@launch } }
                    }
                } finally { synchronized(this@DrainCoalescer) { if (running && !dirty) running = false } }
            }
        }
    }

    private val arrivalDrain = DrainCoalescer()

    /**
     * A frame is durable in the inbox: queue the legacy ingest (the backstop), then commit its receipt (frame row, frame_meta and
     * PENDING frame_state, serialized by the inbox's drain lock) on [scope] and only then wake the pipeline (spec P2.1), so a
     * frame is processed on arrival with no WorkManager in between. The ack does not wait for the commit. The signal runs even
     * when queueing or the commit fails (the failure still reaches the caller / the log); the backstop picks the frame up later.
     */
    internal fun onFrameStored(
        app: Context,
        enqueue: (Context) -> Unit = ::enqueueInboxIngest,
        scope: CoroutineScope = commitScope,
        commit: suspend (Context) -> Unit = ::commitReceipts,
        coalescer: DrainCoalescer = arrivalDrain,
        signal: (Context) -> Unit = { com.rokid.context.work.frames.KeyframeGraph.get(it).signal() },
    ) {
        try { enqueue(app) } finally {
            coalescer.request(scope) {
                try { commit(app) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(Hub.TAG, "receipt commit failed: ${e.javaClass.simpleName}")
                } finally {
                    try { signal(app) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w(Hub.TAG, "keyframe signal failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /** Commits the receipts waiting in the inbox (nothing while the pipeline is paused: frames then only wait there). */
    internal suspend fun commitReceipts(app: Context) {
        if (!AppConfigStore.isPipelineEnabled(app)) return
        val place = com.rokid.context.connectors.sources.SourcesGraph.get(app).placeLabel(System.currentTimeMillis())
        RokidFrameInbox.ingestPending(app, maxItems = 16, placeLabel = place)
    }

    private fun build(app: Context): Hub {
        // Last line of defence: Hub guards every event, but nothing on this scope may ever kill the process.
        val guard = CoroutineExceptionHandler { _, t -> Log.w(Hub.TAG, "hub coroutine failed: ${t.javaClass.simpleName}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
        val ledger = ReceivedFrameLedger(File(app.filesDir, "cxr_received_frames.txt"))
        val settings = HubSettingsStore(app.getSharedPreferences("hub_settings", Context.MODE_PRIVATE)) { AppConfigStore.glassesSilencyMode(app) }
        val pauses = HubPauses(MemoryGraph.get(app).db.pauseDao(), quietHours = { settings.read().quietHours })
        val ingestor = FrameIngestor(
            RokidFrameInbox.inboxDir(app), FrameReassembler(), FrameAdmission(ledger, pauses), ledger,
            archiveDir = RokidFrameInbox.archiveDir(app),
            // A receipt already committed to Room is acked at once (spec P2 receipt durability).
            committed = { wireId -> MemoryGraph.get(app).db.frameMetaDao().byWireId(wireId) != null },
        ) { onFrameStored(app) }
        val link = CxrGlassesLink(app, scope, probe = com.rokid.context.BuildConfig.DEBUG)
        val phoneUse = PhoneUseSignal().also { it.start(app) }
        // Wave B: constructed here, started and stopped with the Hub (no sensing until a source is registered and switched on).
        val ctxRegistry = com.rokid.context.phone.context.ContextSourceRegistry()
        val ctxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + guard)
        val ctxHub = com.rokid.context.phone.context.ContextHub(
            ctxScope, com.rokid.context.phone.context.ContextSourceSettings.of(app), ctxRegistry,
            log = { Log.i("PcCtx", it) },
        )
        ctxRegistry.register(com.rokid.context.phone.context.WatchSource(
            settings = com.rokid.context.phone.context.ContextSourceSettings.of(app),
            installed = { runCatching { app.packageManager.getPackageInfo(com.rokid.context.phone.context.WatchMotionIntake.LIVEFIT_PACKAGE, 0) }.isSuccess },
            sinkFor = ctxHub::sinkFor, onRejected = ctxHub.stats::onWatchRejected, onEpochReset = ctxHub.stats::onWatchEpoch,
        ))
        // Wave B phone sources (opt-in, default off): each takes ctxHub.sinkFor(id) inside start(); nothing runs until switched on.
        ctxRegistry.register(com.rokid.context.phone.context.ActivitySource.create(app, ctxHub::sinkFor))
        ctxRegistry.register(com.rokid.context.phone.context.StepSource.create(app, ctxHub::sinkFor))
        ctxRegistry.register(com.rokid.context.phone.context.AirplaneSource.create(app, ctxHub::sinkFor))
        // phone_car: A "My car" list change while a device is connected re-derives its kind.
        ctxRegistry.register(com.rokid.context.phone.context.CarSource.create(
            app, ctxHub::sinkFor,
            myCars = { com.rokid.context.phone.context.ContextSourceSettings.of(app).myCars },
            changes = com.rokid.context.phone.context.ContextSourceSettings.of(app).changes, scope = ctxScope,
        ))
        // phone_speed: on demand only (the hub calls registry.speed.setWanted while a vehicle is suspected); the service adds the
        // `location` type only while that is wanted, from the front.
        com.rokid.context.phone.hub.ContextHubService.wantLocation = { ctxHub.speedWanted }
        val speed = com.rokid.context.phone.context.SpeedSource.create(
            app, ctxHub::sinkFor, foreground = ContextHubService::appInForeground,
            locationHeld = { ContextHubService.locationHeld.value }, onChange = { ContextHubService.refreshLocationType(app) },
        )
        ctxRegistry.register(speed); ctxRegistry.speed = speed
        // Opt-in, default off. Calls only inside a vehicle episode a listed Bluetooth device started; never pauses or throttles capture.
        ctxRegistry.register(com.rokid.context.phone.context.ImageIntentSource(
            sinkFor = ctxHub::sinkFor, decision = { ctxHub.decision.value }, stats = ctxHub.stats,
            enabled = { com.rokid.context.phone.context.ContextSourceSettings.of(app).enabled(com.rokid.context.phone.context.ImageIntentSource.ID) },
            create = { episode, onResult, stats, enabled ->
                com.rokid.context.work.frames.intent.IntentSamplers.create(app, ctxScope, enabled, episode, onResult, stats)
            },
        ))
        contextRegistry = ctxRegistry; contextHub = ctxHub
        return Hub(
            scope, link, pauses, settings, ingestor, LegacyHubSources(app),
            startService = { ContextHubService.ensureRunning(app) }, voice = com.rokid.context.phone.voice.VoiceGraph.get(app).hubVoice,
            actions = PhoneActions.hubPort(app) { settings.read() }, nudges = PhoneNudges.hubPort(app) { settings.read() },
            reopenGuards = AndroidRelaunchGuards(app, { link.wearing.value }), reopenEnabled = { KeepRunningNearby.of(app).enabled() },
            standDown = PrefsStandDown(app.getSharedPreferences(PrefsStandDown.PREFS, Context.MODE_PRIVATE)),
            phoneUse = phoneUse.inUse,
            motionContext = ctxHub.message, onMotionHint = ctxHub::onMotionHint, ctxStart = ctxHub::start, ctxStop = ctxHub::stop,
            applyToCapture = { com.rokid.context.phone.context.ContextSourceSettings.of(app).applyToCapture },
        )
    }
}
