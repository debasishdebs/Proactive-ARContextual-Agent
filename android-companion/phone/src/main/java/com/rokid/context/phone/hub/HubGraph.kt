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
import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.io.File

/** Process-wide hub (no DI in v1). `ContextApp` has already initialised [MemoryGraph]. */
object HubGraph {
    @Volatile private var hub: Hub? = null

    fun get(context: Context): Hub = hub ?: synchronized(this) { hub ?: build(context.applicationContext).also { hub = it } }

    /** Phase 3 → Phase 4 contract: the real glasses status for the phone UI. */
    fun glassesStatus(context: Context): GlassesStatusSource = get(context)

    internal val isBuilt: Boolean get() = hub != null

    /** Pause zones: the zone state changed (records or the approach flag): the hub pushes settings and a frame. A no-op before the hub is built. */
    internal suspend fun zonesChanged() { hub?.zonesChanged() }

    /** An ask in progress on the glasses, or a Reconnect the user just tapped: keeps the hub service wanted ([BackgroundPolicy.Needs.activeWork]). False before the hub is built. */
    internal fun busy(): Boolean = hub?.busy() == true

    /** Wave B: the one process-wide context hub and its registry; later tasks call `contextRegistry?.register(…)`. Built with the hub, started with it. */
    @Volatile var contextHub: com.rokid.context.phone.context.ContextHub? = null
        private set
    @Volatile var contextRegistry: com.rokid.context.phone.context.ContextSourceRegistry? = null
        private set

    /** Slice 3a: the hub-owned plan reconciliation, the collect session and its control publisher. Built with the hub (so a canvas server stop never touches them). */
    @Volatile var forgetCoordinator: com.rokid.context.canvas.plan.CanvasForgetCoordinator? = null
    @Volatile var planSender: com.rokid.context.phone.hub.plan.PlanSender? = null
        private set
    @Volatile var collectSession: com.rokid.context.canvas.trace.CollectSession? = null
        private set
    @Volatile var traceControl: com.rokid.context.phone.hub.plan.TraceControlPublisher? = null
        private set
    /** The glasses' reported diagnostic flag state and trace blocker, fed by the hub on every Hello (LGC6-min); unknown until then. */
    val diagStatus = com.rokid.context.phone.hub.plan.MutableGlassesDiagStatus()

    /** v0.9.7 slice D: the existing call detection (the relaunch guards), read by the marks (a `call` tag, no photo request while one is active). Null before the hub is built. */
    @Volatile var callGuards: RelaunchGuards? = null
        private set
    fun callActive(): Boolean = callGuards?.callIdle() == false

    /** AutoStop: when our app was last in front (phone clock, 0 = never since this process started); the hub is kept [BackgroundPolicy.FRONT_HOLD_MS] after it. */
    @Volatile internal var lastFrontMs = 0L
    private val autoStopRef = java.util.concurrent.atomic.AtomicReference<AutoStopController>()

    /** The app came to the front or left it: stamps the time and re-checks the AutoStop decision. Safe before the graph is built (it just stamps). */
    internal fun noteFront() { lastFrontMs = System.currentTimeMillis(); autoStopRef.get()?.poke() }

    fun resetForTests(h: Hub?) { synchronized(this) { hub = h } }

    /** Off the main thread: the receipt commit does Room and file I/O. The process-wide hub outlives it. */
    private val commitScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> PcLog.w(Hub.TAG, "receipt commit scope failed: ${t.javaClass.simpleName}") },
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
            ContextHubService.restorePeriodicJobsAsync(app) // v1.1 T7: a frame arrived, so the normal cadence is back (no-op unless reduced)
            coalescer.request(scope) {
                try { commit(app) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    PcLog.w(Hub.TAG, "receipt commit failed: ${e.javaClass.simpleName}")
                } finally {
                    try { signal(app) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        PcLog.w(Hub.TAG, "keyframe signal failed: ${e.javaClass.simpleName}")
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
        val guard = CoroutineExceptionHandler { _, t -> PcLog.w(Hub.TAG, "hub coroutine failed: ${t.javaClass.simpleName}") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + guard)
        val ledger = ReceivedFrameLedger(File(app.filesDir, "cxr_received_frames.txt"))
        val settings = HubSettingsStore(app.getSharedPreferences("hub_settings", Context.MODE_PRIVATE)) { AppConfigStore.glassesSilencyMode(app) }
        val pauses = HubPauses(MemoryGraph.get(app).db.pauseDao(), quietHours = { settings.read().quietHours })
        val ingestor = FrameIngestor(
            RokidFrameInbox.inboxDir(app), FrameReassembler(), FrameAdmission(ledger, pauses, marks = com.rokid.context.link.MarkPhotoAllowance(RoomMarkLookup(MemoryGraph.get(app).db.canvasSlice3Dao()))), ledger,
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
            log = { PcLog.i("PcCtx", it) },
            onAccepted = com.rokid.context.phone.context.DrivingFeed::accepted, onEvaluated = com.rokid.context.phone.context.DrivingFeed::evaluated, // v3.0 T14: the driving signal reads what the hub already has
            overrides = com.rokid.context.phone.ui.settings.capture.FusionSettingsStore.get(app).overrides, // v0.9.7 B: the user's fusion thresholds, applied without a restart
            // the ring is emptied when the watch source goes off: the canvas run rows that carry its values go with it (Delete all already wipes them)
            onWatchOff = { ctxScope.launch { runCatching { com.rokid.context.canvas.WatchRowCleanup.deleteUntilDone(MemoryGraph.get(app).db.canvasDao()) } } },
        )
        ctxRegistry.register(com.rokid.context.phone.context.WatchSource(
            settings = com.rokid.context.phone.context.ContextSourceSettings.of(app),
            installed = { true }, // the Data Layer needs no installed package; the status row reports a missing watch app
            sinkFor = ctxHub::sinkFor, onRejected = ctxHub.stats::onWatchRejected, onEpochReset = ctxHub.stats::onWatchEpoch,
        ))
        com.rokid.context.phone.context.WatchLinkState.shared.offSink = ctxHub.stats::onWatchRejected // a drop with the source off is counted, never silent
        // Wave B phone sources (opt-in, default off): each takes ctxHub.sinkFor(id) inside start(); nothing runs until switched on.
        ctxRegistry.register(com.rokid.context.phone.context.ActivitySource.create(app, ctxHub::sinkFor, ctxHub::failFor))
        ctxRegistry.register(com.rokid.context.phone.context.StepSource.create(app, ctxHub::sinkFor))
        ctxRegistry.register(com.rokid.context.phone.context.AirplaneSource.create(app, ctxHub::sinkFor))
        // phone_car: A "My car" list change while a device is connected re-derives its kind.
        ctxRegistry.register(com.rokid.context.phone.context.CarSource.create(
            app, ctxHub::sinkFor,
            myCars = { com.rokid.context.phone.context.ContextSourceSettings.of(app).myCars },
            changes = com.rokid.context.phone.context.ContextSourceSettings.of(app).changes, scope = ctxScope,
        ))
        // phone_speed: the service takes the `location` type at any open/resume from the front while the switch is on and a location
        // permission is granted (spec §5); fix REQUESTS still follow ctxHub.speedWanted (vehicle suspected) and valid access.
        val watchdogRef = java.util.concurrent.atomic.AtomicReference<GlassesWatchdog>()
        val ctxSettings = com.rokid.context.phone.context.ContextSourceSettings.of(app)
        // Pause zones: the same foreground location type is also held while any enabled zone exists (no background location, no geofencing).
        com.rokid.context.phone.hub.ContextHubService.wantLocation = { ctxSettings.enabled(com.rokid.context.phone.context.SpeedSource.ID) || com.rokid.context.phone.zone.ZoneGraph.get(app).wantsLocation() || com.rokid.context.work.frames.place.PlaceGuideSettings.of(app).enabled }
        var sourcesOn = BackgroundPolicy.CONTEXT_SOURCE_IDS.count(ctxSettings::enabled)
        ctxScope.launch { ctxSettings.changes.collect {
            ContextHubService.refreshLocationType(app)
            val n = BackgroundPolicy.CONTEXT_SOURCE_IDS.count(ctxSettings::enabled)
            if (n > sourcesOn) watchdogRef.get()?.onHubWanted() // a source switched on: the hub restart ladder starts again
            sourcesOn = n
        } }
        val speed = com.rokid.context.phone.context.SpeedSource.create(
            app, ctxHub::sinkFor, foreground = ContextHubService::appInForeground,
            locationHeld = { ContextHubService.locationHeld.value }, onChange = { ContextHubService.refreshLocationType(app) },
        )
        ctxRegistry.register(speed); ctxRegistry.speed = speed
        // Place Guide (PG2): poked after each committed frame; does nothing unless its switch is on, the frame is a dwell photo and no pause zone is active, approaching or unreadable (fail closed).
        com.rokid.context.work.frames.KeyframeGraph.placeSampler?.close()
        // The fence's synchronous second opinion, read at every check: the controller's own flags (the controller also holds the fence itself).
        com.rokid.context.work.frames.place.PlaceGraph.fence.extraBlocked = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.approach || z.enforcing } }
        // Z1: Ask's zone fence reads the controller's flags (approach/enforcing include an unreadable zone list and ZoneHold); a read error is 'blocked' (AskZoneFence).
        com.rokid.context.orchestration.AskZoneFence.extraBlocked = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.approach || z.enforcing } }
        val placeFixes = com.rokid.context.phone.place.PlaceFixProvider.create(app) { com.rokid.context.phone.zone.ZoneGraph.peek() }
        com.rokid.context.work.frames.KeyframeGraph.placeSampler = com.rokid.context.work.frames.place.PlaceSamplers.create(
            app, ctxScope, fix = placeFixes::fresh,
            zoneBlocked = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.approach || z.enforcing } || pauses.zoneRecords().isNotEmpty() },
            onCard = com.rokid.context.work.frames.place.PlaceGraph.port::publish,
        )
        // Opt-in, default off. Calls only inside a vehicle episode a listed Bluetooth device started; never pauses or throttles capture.
        ctxRegistry.register(com.rokid.context.phone.context.ImageIntentSource(
            sinkFor = ctxHub::sinkFor, decision = { ctxHub.decision.value }, stats = ctxHub.stats,
            enabled = { com.rokid.context.phone.context.ContextSourceSettings.of(app).enabled(com.rokid.context.phone.context.ImageIntentSource.ID) },
            create = { episode, onResult, stats, enabled ->
                com.rokid.context.work.frames.intent.IntentSamplers.create(app, ctxScope, enabled, episode, onResult, stats)
            },
        ))
        contextRegistry = ctxRegistry; contextHub = ctxHub
        // Slice 3a: plan reconciliation and the collect session live HERE, in the hub; the canvas server only calls them.
        val install = { com.rokid.context.memory.db.DeletionEpochStore.of(app).read().installId }
        val phoneRt = com.rokid.context.phone.hub.plan.LaterSeams.noPhoneRt // TODO(Task 13): PhoneRtApplier
        val sender = com.rokid.context.phone.hub.plan.PlanSender(
            link, app.getSharedPreferences(com.rokid.context.phone.hub.plan.PlanSender.PREFS, Context.MODE_PRIVATE), install, onDesired = phoneRt::apply,
            epochCounter = { com.rokid.context.memory.db.DeletionEpochStore.of(app).read().counter },
        )
        val collect = com.rokid.context.canvas.trace.CollectSession.of(app)
        val ctl = com.rokid.context.phone.hub.plan.TraceControlPublisher(link, collect, scope).also { it.start() }
        // LGC4P: Developer mode drives `HudSettings.diagRecord`; [diagStatus] is fed by every Hello (LGC6-min); until the first one it is unknown and the re-send is bounded.
        val devMode = com.rokid.context.phone.canvas.DeveloperModeStore.get(app).enabled
        val diagRetry = com.rokid.context.phone.hub.plan.DiagControlRetry(
            scope, diagStatus, diagWanted = com.rokid.context.phone.hub.plan.DiagControlRetry.wantedWhen(devMode, link.state), resend = ctl::resend,
            log = { m -> com.rokid.context.phone.log.PhoneLogging.canvasPort().log(com.rokid.context.core.log.Level.I, "DiagControl", m) },
        )
        val traceIntake = com.rokid.context.phone.hub.plan.LaterSeams.noTraceIntake // TODO(Task 18): TraceIntake
        planSender = sender; collectSession = collect; traceControl = ctl
        // Task 33: the publish groups sit behind the same transport; their graph is built on first use (it needs the AI graph) and warmed here so recover() runs at startup.
        val hubRef = java.util.concurrent.atomic.AtomicReference<Hub>()
        com.rokid.context.phone.canvas.PlanGraph.install(
            app, scope, sender, install,
            glasses = { hubRef.get()?.let { com.rokid.context.phone.canvas.PlanGraph.glassesState(it.state.value.link, it.planPeer()) } ?: com.rokid.context.canvas.plan.GlassesState.UNKNOWN },
            phoneRt = phoneRt::apply,
        )
        com.rokid.context.phone.canvas.PlanGraph.warm(scope)

        // S57: a Delete all whose cleanup was cut short (a crash, a failed delete) is finished as the hub comes up.
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { try { com.rokid.context.orchestration.MemoryWipe.resumeIfPending(app) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(Hub.TAG, "wipe resume failed (${e.javaClass.simpleName})") } }
        // S58: files and glasses follow every Delete all and time-range Forget; registered once (the hub graph is a process singleton).
        forgetCoordinator?.let { com.rokid.context.memory.forget.ForgetListeners.unregister(it) }
        com.rokid.context.memory.forget.ForgetListeners.register(
            com.rokid.context.canvas.plan.CanvasForgetCoordinator(
                com.rokid.context.memory.forget.CanvasSlice3Forget(com.rokid.context.memory.MemoryGraph.get(app).db), app.filesDir, collect,
                send = { c -> link.send(com.rokid.context.core.model.Channels.TRACE_CTL, com.rokid.context.core.model.Wire.encode(c)) },
                onWiped = sender::resetAfterWipe,
                afterCascade = { com.rokid.context.orchestration.forget.ForgetActions.afterRange(app, it) },
            ).also { forgetCoordinator = it },
        )
        return Hub(
            scope, link, pauses, settings, ingestor, LegacyHubSources(app),
            startService = { ContextHubService.ensureRunning(app, force = true) }, voice = com.rokid.context.phone.voice.VoiceGraph.get(app).hubVoice,
            actions = PhoneActions.hubPort(app) { settings.read() }, nudges = PhoneNudges.hubPort(app) { settings.read() },
            reopenGuards = AndroidRelaunchGuards(app, { link.wearing.value }).also { callGuards = it }, reopenEnabled = { KeepRunningNearby.of(app).enabled() }, paired = { com.rokid.context.link.CxrGlassesLink.isPaired(app) },
            onPositiveSignal = { watchdogRef.get()?.onPositiveSignal() }, inFront = ContextHubService::appInForeground,
            standDown = PrefsStandDown(app.getSharedPreferences(PrefsStandDown.PREFS, Context.MODE_PRIVATE)),
            relaunchStore = PrefsRelaunchStore(app.getSharedPreferences(PrefsStandDown.PREFS, Context.MODE_PRIVATE)),
            phoneUse = phoneUse.inUse,
            motionContext = ctxHub.message, onMotionHint = ctxHub::onMotionHint, ctxStart = ctxHub::start, ctxStop = ctxHub::stop,
            applyToCapture = { com.rokid.context.phone.context.ContextSourceSettings.of(app).applyToCapture },
            phoneInstall = install, onPlanAck = { ack -> sender.onAck(ack); com.rokid.context.phone.canvas.PlanGraph.feedAck(ack) }, onTraceChunk = traceIntake::onChunk,
            onMark = { m, a -> com.rokid.context.phone.canvas.MarkGraph.recorder(app).onMark(m, a).also { handled -> if (handled && m.undo == null) com.rokid.context.phone.nexus.NexusGraph.onMarkSaved("mk:" + m.epoch + ":" + m.seq) } },
            onMarkPhoto = { r -> com.rokid.context.phone.canvas.MarkGraph.recorder(app).onPhotoResult(r) },
            onGlassesListenStart = { t -> com.rokid.context.phone.canvas.MarkGraph.recorder(app).onListenStart(t) },
            onSessionReady = { start -> sender.onSessionReady(start); ctl.onSessionReady() },
            devMode = devMode, diagControl = diagRetry, diagStatus = diagStatus,
            placeCards = com.rokid.context.work.frames.place.PlaceGraph.port.card, placeClear = com.rokid.context.work.frames.place.PlaceGraph.port::clear, placeClearIf = com.rokid.context.work.frames.place.PlaceGraph.port::clearIf,
            zoneApproach = { com.rokid.context.phone.zone.ZoneGraph.get(app).approach },
            zoneEnforce = { com.rokid.context.phone.zone.ZoneGraph.get(app).enforcing },
            // Every hub start and recovery judges the zones again from "nothing known" (not only the graph's construction): see ZoneController.start.
            zoneStart = { com.rokid.context.phone.zone.ZoneGraph.get(app).let { z -> z.beginRestart(); scope.launch { z.start() } } },
            // v2.0 (Rokid Nexus, opt-in, default PCA): who draws the cards, and the stop hook that withdraws them from Nexus.
            hudHost = { com.rokid.context.phone.nexus.NexusHostStore.of(app).current() },
            onStopped = com.rokid.context.phone.nexus.NexusGraph::onHubStopped,
        ).also { hubRef.set(it) }.also { hub ->
            com.rokid.context.phone.nexus.NexusGraph.attach(app, scope, hub) // binds to nothing until the user picks Nexus as the HUD host
            // Pause zones: the zones are judged at every hub start (Hub.start -> zoneStart); here also ask for fixes only while zones exist, under the hub's own foreground location type.
            com.rokid.context.phone.zone.ZoneGraph.get(app).let { zones ->
                scope.launch { zones.start() } // and at the graph's construction too (the map UI and "Capture now" can run before the hub does)
                com.rokid.context.phone.zone.TripLocation.create(app, zones, scope, foreground = ContextHubService::appInForeground, locationHeld = { ContextHubService.locationHeld.value }).start()
            }
            // v0.9.8 slice C: a flow's glasses delivery target asks this (not connected = the phone, with the reason recorded)
            com.rokid.context.orchestration.DeliveryEnvironment.glassesConnected = { hub.link.state.value == com.rokid.context.core.model.LinkState.CONNECTED }
            // Process-lifetime watchdog: it outlives the hub service, so it can also bring a dead service back (ensureRunning is refused harmlessly where Android forbids it).
            // v0.9.9: with no pair or the setting off it does nothing (and stops a hub nothing needs); away it backs off 5/15/30/60 min ([BackgroundPolicy]).
            GlassesWatchdog(
                now = System::currentTimeMillis, enabled = { KeepRunningNearby.of(app).enabled() },
                captureActive = { hub.activePauses.value.isEmpty() }, hubRunning = { hub.started }, link = { hub.link.state.value },
                lastHeardAtMs = { hub.lastHeardAtMs }, standingDown = hub::standingDown,
                startHub = { ContextHubService.ensureRunning(app) }, reconnect = hub::watchdogReconnect, probeAlive = hub::watchdogProbe,
                relaunch = hub::watchdogRelaunch, publish = hub::setWatchdogNote,
                // Settings > Capture: the silence threshold and the tick, read at every tick (defaults 3 min and 150 s, as before)
                silentMs = com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(app)::silentMs,
                periodMs = com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(app)::tickMs,
                paired = { com.rokid.context.link.CxrGlassesLink.isPaired(app) },
                hubNeeded = { ContextHubService.appInForeground() || BackgroundInputs.hubNeeded(app) }, stopHub = { ContextHubService.stop(app) },
                ladderOwner = AutoStopWake.hubLadder(app), // the ONE owner of the hub restart ladder, shared with the lost-hub recovery; it commits on IO, never on the loop's main thread
            ).also { watchdogRef.set(it) }.start(scope)
            // AutoStop (user ruling 2026-11-07): the hub stops by itself after the glasses were off-head or disconnected for N minutes; a woken hub (battery exemption) looks for them first.
            val timing = com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.get(app)
            AutoStopController(
                now = System::currentTimeMillis, minutes = timing::autoStopMinNow, hubRunning = { hub.started },
                link = { hub.link.state.value }, worn = hub::wornNow, wornEstimated = { hub.wearEstimate.active.value }, inFront = ContextHubService::appInForeground, lastFrontMs = { lastFrontMs },
                busy = hub::busy, runActive = { collectSession?.active() == true },
                stopHub = { ContextHubService.stop(app) }, wakeHub = { ContextHubService.ensureRunning(app, wake = true) },
                exempt = { AutoStopWake.exempt(app) },
                linkAllowed = { BackgroundPolicy.linkMachineryAllowed(KeepRunningNearby.of(app).enabled(), com.rokid.context.link.CxrGlassesLink.isPaired(app)) },
                // effects run on the AutoStop actor's IO context: the note goes back to the hub's own thread
                publishNote = { n -> scope.launch { hub.setAutoStopNote(n) } }, scheduleWake = { AutoStopWake.schedule(app, it) }, serviceRunning = ContextHubService::isRunning,
                engine = AutoStopWake.engine(app), // the ONE actor the worker, the process start and the service's user start / manual stop also feed
            ).also { ctl ->
                autoStopRef.set(ctl)
                scope.launch { kotlinx.coroutines.flow.combine(hub.link.state, hub.link.wearing, hub.wearEstimate.active) { l, w, e -> Triple(l, w, e) }.collect { ctl.poke() } }
                scope.launch { timing.autoStopMin.collect { ctl.poke() } }
                // v1.1 T6: the glasses' Bluetooth address is learned while the link is up (the ACL receiver matches only it).
                scope.launch { hub.link.state.collect { if (it == com.rokid.context.core.model.LinkState.CONNECTED) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GlassesAclReceiver.learn(app) } } }
            }.start(scope)
        }
    }
}
