package com.rokid.context.phone.canvas

import android.content.Context
import com.rokid.context.canvas.plan.ReplayService
import com.rokid.context.canvas.trace.CanvasStartup
import com.rokid.context.canvas.trace.StoredReplayTraces
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.phone.canvas.api.ApiExtra
import com.rokid.context.phone.canvas.api.PlanRoutes
import com.rokid.context.phone.canvas.api.ReplayRoutes
import com.rokid.context.phone.canvas.api.TraceRoutes
import com.rokid.context.phone.hub.HubGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Slice 3a composition (Task 22): the route groups the canvas API adds (traces and collect, replays, plans) and the replay service behind them.
 * The plan sender and the collect session are the HUB's (see [HubGraph]); the canvas only holds references to them, read at call time, so stopping the
 * canvas server (or Developer mode going off) changes nothing about what the glasses are asked to do.
 */
class CanvasExtras(val extras: List<ApiExtra>, val replay: ReplayService, private val startup: suspend () -> Unit) {
    /** Once per server start: orphan tmp files go and a replay left RUNNING by a dead process is ABORTED. */
    suspend fun start() = startup()

    companion object {
        fun create(app: Context, scope: CoroutineScope, devMode: StateFlow<Boolean>): CanvasExtras {
            val db = MemoryGraph.get(app).db
            val dao = db.canvasSlice3Dao()
            val epoch = DeletionEpochStore.of(app)
            val fence = { DaoFence(MemoryWipe.generation.get(), epoch.read(), MemoryWipe.generation::get, epoch::read) }
            // TODO(later): a thermal-severe flow for ReplayService (it also stops on shutdown, Developer mode off and the user).
            val replay = ReplayService(dao, StoredReplayTraces(dao, File(app.filesDir, com.rokid.context.canvas.trace.FencedFiles.TRACES_DIR)), fence, scope, devMode)
            return build(
                dao, replay, devMode, startup = { CanvasStartup.run(app, db) },
                sender = { HubGraph.get(app); HubGraph.planSender }, collect = { HubGraph.get(app); HubGraph.collectSession },
            )
        }

        /** The pure assembly, so a test can pass its own dao, replay service and hub references. */
        fun build(
            dao: com.rokid.context.memory.db.CanvasSlice3Dao, replay: ReplayService, devMode: StateFlow<Boolean>, startup: suspend () -> Unit,
            sender: () -> com.rokid.context.phone.hub.plan.PlanPublishPort?, collect: () -> com.rokid.context.canvas.trace.CollectSession?,
            /** Task 27/28: the gated publish and the S26 actions, and the M1 record; null until the composition task (33) binds them (the routes then answer 503). */
            plans: () -> com.rokid.context.canvas.plan.CapturePlanPublisher? = { null },
            m1: () -> com.rokid.context.canvas.plan.M1EvidenceStore? = { null },
        ): CanvasExtras {
            val traces = TraceRoutes(dao, collect, devMode::value)
            val replays = ReplayRoutes(replay)
            val planRoutes = PlanRoutes(devMode::value, sender, plans = plans, m1 = m1)
            return CanvasExtras(listOf(ApiExtra(traces::handle), ApiExtra(replays::handle), ApiExtra(planRoutes::handle)), replay, startup)
        }
    }
}
