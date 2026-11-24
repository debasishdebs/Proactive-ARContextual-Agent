package com.rokid.context.phone.canvas

import android.content.Context
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.plan.SweepService
import com.rokid.context.canvas.trace.CanvasStartup
import com.rokid.context.canvas.trace.StoredReplayTraces
import com.rokid.context.canvas.trace.TraceImport
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.api.ApiExtra
import com.rokid.context.phone.canvas.api.MomentRoutes
import com.rokid.context.phone.canvas.api.PlanRoutes
import com.rokid.context.phone.canvas.api.ReplayRoutes
import com.rokid.context.phone.canvas.api.SweepRoutes
import com.rokid.context.phone.canvas.api.TraceRoutes
import com.rokid.context.phone.hub.HubGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Slice 3a composition (Task 22): the route groups the canvas API adds (traces and collect, replays, sweeps, plans) and the replay service behind them.
 * The plan sender and the collect session are the HUB's (see [HubGraph]); the canvas only holds references to them, read at call time, so stopping the
 * canvas server (or Developer mode going off) changes nothing about what the glasses are asked to do.
 */
class CanvasExtras(val extras: List<ApiExtra>, val replay: ReplayService, private val startup: suspend () -> Unit, val sweeps: SweepService? = null) {
    /** Once per server start: orphan tmp files go and a replay left RUNNING by a dead process is ABORTED. */
    suspend fun start() = startup()

    companion object {
        fun create(app: Context, scope: CoroutineScope, devMode: StateFlow<Boolean>): CanvasExtras {
            val db = MemoryGraph.get(app).db
            val dao = db.canvasSlice3Dao()
            val epoch = DeletionEpochStore.of(app)
            val fence = { DaoFence(MemoryWipe.generation.get(), epoch.read(), MemoryWipe.generation::get, epoch::read) }
            // TODO(later): a thermal-severe flow for ReplayService (it also stops on shutdown, Developer mode off and the user).
            val traces = StoredReplayTraces(dao, File(app.filesDir, com.rokid.context.canvas.trace.FencedFiles.TRACES_DIR), epoch::read)
            val gate = kotlinx.coroutines.sync.Mutex()   // S77: one replay or sweep job at a time
            val replay = ReplayService(dao, traces, fence, scope, devMode, jobGate = gate)
            val sweeps = SweepService(dao, traces, fence, scope, devMode, jobGate = gate)
            return build(
                dao, replay, devMode, startup = { CanvasStartup.run(app, db) },
                moments = { f, t, n -> com.rokid.context.canvas.moment.MomentTriggers.builder(app).rebuildDay(f, t, n) },
                momentScenes = { f, t -> com.rokid.context.canvas.moment.SceneTriggers.builder(app).rebuildWindow(f, t) },
                momentRepair = { n -> com.rokid.context.canvas.moment.MomentRepair(db, com.rokid.context.canvas.moment.MomentTriggers.builder(app), com.rokid.context.canvas.moment.SceneTriggers.builder(app)).run(n) },
                momentLocationImport = com.rokid.context.phone.canvas.api.MomentLocationRoutes(
                    devMode::value, settingOn = { com.rokid.context.orchestration.forget.DeveloperFlags.momentLocation(app) },
                    zoneBlocked = { HubGraph.get(app); com.rokid.context.canvas.moment.MomentLocationGraph.zoneBlockedNow() },
                    import = com.rokid.context.canvas.moment.MomentLocationImport(
                        db, System::currentTimeMillis, { com.rokid.context.orchestration.forget.DeveloperFlags.momentLocation(app) },
                        { HubGraph.get(app); com.rokid.context.canvas.moment.MomentLocationGraph.zonesNow() }, { MemoryWipe.generation.get() },
                    ),
                ),
                sender = { HubGraph.get(app); HubGraph.planSender }, collect = { HubGraph.get(app); HubGraph.collectSession },
                importer = TraceImport(dao, com.rokid.context.canvas.trace.FencedFiles(db, app.filesDir), fence, onImported = com.rokid.context.canvas.moment.MomentTriggers.get(app)::onTraceImported),
                stagingRoot = { app.getExternalFilesDir(null)?.let { File(it, com.rokid.context.canvas.trace.FencedFiles.STAGING_DIR).also(File::mkdirs) } },
                plans = { HubGraph.get(app); PlanGraph.current()?.publisher }, m1 = { HubGraph.get(app); PlanGraph.current()?.m1 }, sweeps = sweeps,
                candidates = com.rokid.context.canvas.plan.ReplayCandidates(db.canvasDao())::forHash,
                signals = com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(app),
                devices = com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore.get(app),
                proactiveReplay = DeveloperModeStore.get(app).let { dev ->
                    com.rokid.context.phone.canvas.api.ProactiveReplayRoute(dev.enabled, com.rokid.context.orchestration.proactive.ProactiveSettings.of(app).replay, replay = { day, step, extra ->
                        com.rokid.context.orchestration.proactive.ProactiveGraph.replayer(app).replay(day, step, extra)
                    }, counters = { if (com.rokid.context.orchestration.proactive.ProactiveGraph.storageAvailable(app)) com.rokid.context.orchestration.proactive.ProactiveGraph.store(app).counters() else emptyMap() })
                },
                shadowEval = DeveloperModeStore.get(app).let { dev -> com.rokid.context.phone.canvas.api.ShadowEvalRoute(dev.enabled) { shadowEvalInput(app) } },
                signalTry = DeveloperModeStore.get(app).let { dev ->
                    com.rokid.context.phone.canvas.api.SignalTry(
                        com.rokid.context.canvas.signal.SignalTryReader(db), { com.rokid.context.phone.ui.settings.capture.SignalConfigStore.get(app).config.value },
                        dev.enabled, dev.signalTryEnabled, com.rokid.context.work.frames.KeyframeGraph.systemThermal(app),
                    )
                },
            )
        }

        /** The pure assembly, so a test can pass its own dao, replay service and hub references. */
        fun build(
            dao: com.rokid.context.memory.db.CanvasSlice3Dao, replay: ReplayService, devMode: StateFlow<Boolean>, startup: suspend () -> Unit,
            sender: () -> com.rokid.context.phone.hub.plan.PlanPublishPort?, collect: () -> com.rokid.context.canvas.trace.CollectSession?,
            /** Task 27/28: the gated publish and the S26 actions, and the M1 record; null until the composition task (33) binds them (the routes then answer 503). */
            importer: TraceImport? = null, stagingRoot: () -> File? = { null },
            plans: () -> com.rokid.context.canvas.plan.CapturePlanPublisher? = { null },
            m1: () -> com.rokid.context.canvas.plan.M1EvidenceStore? = { null },
            /** Task 24/36: the sweep jobs; null = the routes are not registered (a test of another group). */
            sweeps: SweepService? = null,
            /** The candidate of the stored plan with a hash (`ReplayCandidates.forHash`); null = replays with a candidate are refused. */
            candidates: (suspend (String) -> com.rokid.context.capture.replay.ReplayCandidate?)? = null,
            /** The developer Moment rebuild of one day (`POST /api/moments/rebuild`); null = the route is not registered. */
            moments: (suspend (Long, Long, Long) -> com.rokid.context.canvas.moment.MomentBuilder.DayReport)? = null,
            /** P1: the Scene rebuild of that day, reported as `scenes` by the same route. */
            momentScenes: (suspend (Long, Long) -> com.rokid.context.canvas.moment.SceneBuilder.Report)? = null,
            /** P1: `POST /api/moments/repair`, the one-shot Moment and Scene repair; null = 404. */
            momentRepair: (suspend (Long) -> com.rokid.context.canvas.moment.MomentRepair.Report)? = null,
            /** v18 WP5: `POST /api/moments/locations/import`, the GPS backfill import; null = not registered (404). */
            momentLocationImport: com.rokid.context.phone.canvas.api.MomentLocationRoutes? = null,
            /** v0.9.7 A: the signal configuration store (`/api/signals`); null = the routes are not registered. */
            signals: com.rokid.context.phone.ui.settings.capture.SignalConfigStore? = null,
            /** v0.9.8 slice D: the device capability store (`/api/devices`); null = the routes are not registered. */
            devices: com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore? = null,
            /** v1.2 row 2: the real-data derived-signal preview behind `POST /api/signals/try`; null = the route answers 404. */
            signalTry: com.rokid.context.phone.canvas.api.SignalTry? = null,
            /** v3.0 shadow loop: the developer dry-run replay (`POST /api/proactive/replay`); null = the route is not registered (404). */
            proactiveReplay: com.rokid.context.phone.canvas.api.ProactiveReplayRoute? = null,
            /** Developer-only `GET /api/proactive/shadow-eval` (the shadow-week report); null = not registered (404). */
            shadowEval: com.rokid.context.phone.canvas.api.ShadowEvalRoute? = null,
        ): CanvasExtras {
            val traces = TraceRoutes(dao, collect, devMode::value, importer = { importer }, stagingRoot = stagingRoot)
            val replays = ReplayRoutes(replay, candidates ?: { null })
            val planRoutes = PlanRoutes(devMode::value, sender, plans = plans, m1 = m1)
            val all = buildList {
                add(ApiExtra(traces::handle)); add(ApiExtra(replays::handle))
                if (sweeps != null) SweepRoutes(sweeps).let { add(ApiExtra(it::handle)) }
                add(ApiExtra(planRoutes::handle))
                if (moments != null) MomentRoutes(devMode::value, moments, scenes = momentScenes, repair = momentRepair).let { add(ApiExtra(it::handle)) }
                momentLocationImport?.let { add(ApiExtra(it::handle)) }
                add(ApiExtra(com.rokid.context.phone.canvas.api.ExprRoutes()::handle))
                signals?.let { add(ApiExtra(com.rokid.context.phone.canvas.api.SignalRoutes(it, signalTry)::handle)) }
                devices?.let { add(ApiExtra(com.rokid.context.phone.canvas.api.DeviceRoutes(it)::handle)) }
                proactiveReplay?.let { add(ApiExtra(it::handle)) }
                shadowEval?.let { add(ApiExtra(it::handle)) }
            }
            return CanvasExtras(all, replay, startup, sweeps)
        }

        /**
         * The shadow-week report input from the REAL stores (read-only; works with every delivery switch OFF). Quiet and zone come from the stored pause intervals at each record's time (a pause that covers it that is not a
         * `zone:` one = quiet; a `zone:` one = zone); a read that fails counts as a leak (fail closed). Driving and wear keep no history, so they are null (UNCHECKED). The store has only cumulative counters: they go in as the overall histogram.
         */
        internal suspend fun shadowEvalInput(app: Context): com.rokid.context.orchestration.proactive.ShadowEvalInput {
            val g = com.rokid.context.orchestration.proactive.ProactiveGraph
            if (!g.storageAvailable(app)) return com.rokid.context.orchestration.proactive.ShadowEvalInput(emptyList(), emptyMap(), com.rokid.context.orchestration.proactive.FeedbackState())
            val st = g.store(app)
            val records = st.records()
            val pauses = MemoryGraph.get(app).db.pauseDao()
            val quiet = HashMap<Long, Boolean>(); val zone = HashMap<Long, Boolean>()
            for (ts in records.map { it.ts }.toSet()) {
                try { val all = pauses.countCovering(ts); val z = pauses.countZoneCovering(ts); zone[ts] = z > 0; quiet[ts] = all - z > 0 } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { zone[ts] = true; quiet[ts] = true }
            }
            return com.rokid.context.orchestration.proactive.ShadowEvalInput(
                records = records, counters = st.counters(), feedback = st.feedback(), neverCount = st.never().size,
                quietAt = { quiet[it] ?: true }, zoneBlocked = { zone[it.ts] ?: true },
            )
        }
    }
}
