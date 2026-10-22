package com.rokid.context.phone.canvas

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.canvas.plan.CapturePlanPublisher
import com.rokid.context.canvas.plan.DaoGroupStore
import com.rokid.context.canvas.plan.DaoM1EvidenceStore
import com.rokid.context.canvas.plan.GlassesState
import com.rokid.context.canvas.plan.M1EvidenceStore
import com.rokid.context.canvas.plan.MetaPlanSource
import com.rokid.context.canvas.plan.PlanActivation
import com.rokid.context.canvas.plan.PlanEvidenceGates
import com.rokid.context.canvas.plan.PlanPublishGroups
import com.rokid.context.canvas.plan.PlanTransport
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.LinkState
import com.rokid.context.link.PlanPeer
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.phone.ui.settings.capture.CapturePlanControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Slice 3a Task 33: the one composition of the capture-plan publish path of the process. The hub owns the transport ([com.rokid.context.phone.hub.plan.PlanSender]) and
 * installs this graph ([install]); everything else reads it ([of]).
 *
 * What it binds: the hub's matching acks into [PlanPublishGroups.onAck] ([onAck], one consumer so acks apply in order), [PlanPublishGroups.recover] once at start and
 * [PlanPublishGroups.tick] every [tickEveryMs]; the [GlassesState] supplier of gate 5; the [CapturePlanPublisher] over the ONE [CanvasPublisher] of the process (the canvas
 * backend uses [canvasPublisher] too); the Settings row ([control]) and the Diagnostics card (`publisher.status()`).
 */
class PlanGraph(
    canvasDao: CanvasDao,
    dao: CanvasSlice3Dao,
    val canvasPublisher: CanvasPublisher,
    transport: PlanTransport,
    install: () -> String,
    glasses: () -> GlassesState,
    /** Task 13 seam: the phone RT half of an EFFECTIVE plan (null = use settings). */
    phoneRt: (String?) -> Unit,
    private val scope: CoroutineScope,
    clock: () -> Long = System::currentTimeMillis,
    private val tickEveryMs: Long = 60_000L,
    private val log: (String) -> Unit = {},
) {
    val store = DaoGroupStore(dao)
    val m1: M1EvidenceStore = DaoM1EvidenceStore(dao)
    private val activation = PlanActivation(canvasDao, canvasPublisher, phoneRt, clock)
    val groups = PlanPublishGroups(store, transport, install, clock, activation::activate, MetaPlanSource(dao), activation::deactivate)
    val gates = PlanEvidenceGates(dao, m1, glasses, canvasDao)
    val publisher = CapturePlanPublisher(canvasDao, groups, store, gates, canvasPublisher, clock)

    private val acks = Channel<CapturePlanAck>(Channel.UNLIMITED)
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val started = AtomicBoolean(false)

    /** An ack the hub already classified as this install and session (the hub's `onPlanAck`). Never blocks. */
    fun onAck(ack: CapturePlanAck) { acks.trySend(ack) }

    /** Once: recovery after a restart, then acks in order, and the periodic tick. */
    fun start(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch {
            guarded("recover") { groups.recover() }
            changed.tryEmit(Unit)
            launch { for (a in acks) { guarded("ack") { groups.onAck(a) }; changed.tryEmit(Unit) } }
            while (isActive) { delay(tickEveryMs); guarded("tick") { groups.tick() }; changed.tryEmit(Unit) }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("plan $what failed (${e.javaClass.simpleName})") }
    }

    /** Settings > Capture: the active plan (re-read on every change and every [POLL_MS]) and the "Use settings" action. */
    val control: CapturePlanControl = object : CapturePlanControl {
        override val active = merge(changed, flow { while (true) { emit(Unit); delay(POLL_MS) } }).onStart { emit(Unit) }
            .map { try { publisher.status().active } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
            .distinctUntilChanged()
        override suspend fun useSettings() { publisher.useSettings(); changed.tryEmit(Unit) }
    }

    companion object {
        const val POLL_MS = 5_000L
        private const val TAG = "PlanGraph"
        @Volatile private var installed: Lazy<PlanGraph>? = null

        /** Gate 5 from what the hub knows: linked and a caps ack seen = capable. */
        fun glassesState(link: LinkState, peer: PlanPeer): GlassesState = when {
            link != LinkState.CONNECTED -> GlassesState.NOT_LINKED
            peer == PlanPeer.CAPABLE -> GlassesState.LINKED_CAPABLE
            peer == PlanPeer.TOO_OLD -> GlassesState.TOO_OLD
            else -> GlassesState.UNKNOWN
        }

        /**
         * Called by the hub's builder with its transport. The graph is built on first use (it needs the AI graph for the publisher) and started the moment it is built;
         * [warm] forces that off the main thread at process start so [PlanPublishGroups.recover] runs without waiting for an ack.
         */
        fun install(app: Context, scope: CoroutineScope, transport: PlanTransport, install: () -> String, glasses: () -> GlassesState, phoneRt: (String?) -> Unit) {
            installed = lazy {
                val ai = AiGraph.get(app)
                val db = MemoryGraph.get(app).db
                val publisher = CanvasPublisher(db.canvasDao(), ai.prompts, { key, draft -> ai.testBed.compatibility(key, draft) }, System::currentTimeMillis, CanvasPublisher.inTransaction(db))
                PlanGraph(db.canvasDao(), db.canvasSlice3Dao(), publisher, transport, install, glasses, phoneRt, scope, log = { Log.i(TAG, it) }).also { it.start() }
            }
        }

        fun installForTests(g: PlanGraph?) { installed = g?.let { lazy { it } } }

        /** The process graph, or null before the hub installed it. */
        fun current(): PlanGraph? = installed?.value
        fun warm(scope: CoroutineScope) { scope.launch(Dispatchers.IO) { runCatching { current() } } }

        /** The hub's ack feed (a no-op before [install]). */
        fun feedAck(ack: CapturePlanAck) { try { current()?.onAck(ack) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "plan graph unavailable (${e.javaClass.simpleName})") } }
    }
}

/** Settings and Diagnostics read the plan through here: nothing is built until the plan graph exists, and a missing graph shows no plan. */
class ProcessPlanControl(private val graph: () -> PlanGraph?) : CapturePlanControl {
    override val active: Flow<com.rokid.context.canvas.plan.ActivePlan?> = flow { graph()?.control?.active?.collect { emit(it) } ?: emit(null) }.flowOn(Dispatchers.IO)
    override suspend fun useSettings() { graph()?.control?.useSettings() }
}
