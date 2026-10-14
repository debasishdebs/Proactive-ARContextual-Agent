package com.rokid.context.connectors.actions

import android.content.Context
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.executors.ActionExecutor
import com.rokid.context.connectors.actions.executors.CalendarExecutor
import com.rokid.context.connectors.actions.executors.NoteExecutor
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.connectors.actions.executors.ShoppingExecutor
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ActionLogEntity
import com.rokid.context.memory.db.NoteDao
import com.rokid.context.memory.db.ShoppingDao
import com.rokid.context.connectors.tools.ToolConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The action engine's process-wide graph (R-62). */
interface ActionsGraphApi {
    val intake: ProposalIntake
    val engine: ActionEngine
    val settings: ActionSettingsStore
    val shopping: ShoppingDao
    /** User notes (Settings › Actions & tools › Notes). */
    val notes: NoteDao
    /** Registered tool connectors (R-63): none in v1. */
    val tools: List<ToolConnector>
}

/**
 * One engine per process, built lazily by [get] (workers call it in processes where the hub never ran; no install needed).
 * [install] (the phone hub) supplies the HUD verbosity. [ActionEngine.recover] runs once, on the first [get] or at
 * [install], whichever comes first ([RecoverOnce]). Before install the verbosity
 * reads MEDIUM, so a NUDGE never asks for a HUD Confirm (the conservative choice).
 */
object ActionsGraph {
    @Volatile private var instance: Impl? = null
    @Volatile private var verbosity: () -> Verbosity = { Verbosity.MEDIUM }

    private val _ready = MutableStateFlow<ActionsGraphApi?>(null)
    /** Non-null once the graph exists and its settings prefs were read on IO: screens collect this (never read prefs on Main). */
    val ready: StateFlow<ActionsGraphApi?> = _ready.asStateFlow()

    fun get(context: Context): ActionsGraphApi = instance ?: synchronized(this) {
        instance ?: Impl(context.applicationContext).also { g ->
            instance = g
            g.scope.launch(Dispatchers.IO) { runCatching { g.settings.settings.value }; _ready.value = g }
            g.recoverOnce.run { g.engine }
        }
    }

    fun install(context: Context, verbosity: () -> Verbosity) {
        this.verbosity = verbosity
        val g = get(context) as Impl
        g.recoverOnce.run { g.engine }
    }

    /** The production executors. No ToolConnector is registered in v1 (R-63: MCP is "Coming in v2"). */
    internal fun executors(app: Context, memory: MemoryGraph): Map<ActionType, ActionExecutor> = mapOf(
        ActionType.REMINDER to ReminderExecutor(app),
        ActionType.SHOPPING_ADD to ShoppingExecutor(memory.shopping),
        ActionType.CALENDAR_EVENT to CalendarExecutor(app.contentResolver),
        ActionType.NOTE_ADD to NoteExecutor(memory.notes),
    )

    private class Impl(app: Context) : ActionsGraphApi {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recoverOnce = RecoverOnce(scope)
        private val memory by lazy { MemoryGraph.get(app) }
        override val settings = ActionSettingsStore { app.getSharedPreferences(ActionSettingsStore.PREFS, Context.MODE_PRIVATE) }
        override val engine: ActionEngine by lazy {
            ActionEngine(
                memory.proposals, settings, executors(app, memory), { verbosity() }, scope, Dispatchers.IO, System::currentTimeMillis,
                audit = { id, transition, detail, now -> memory.db.actionDao().log(ActionLogEntity(ts = now, proposalId = id, transition = transition, detail = detail)) },
            )
        }
        override val shopping: ShoppingDao get() = memory.shopping
        override val notes: NoteDao get() = memory.notes
        override val tools: List<ToolConnector> = emptyList()
        override val intake: ProposalIntake = ProposalIntake { o, id, a, now -> engine.onProposed(o, id, a, now) }
    }
}

/**
 * Runs [ActionEngine.recover] at most once per process (integration carry 6): on the first lazy [ActionsGraph.get] (a
 * worker or receiver in a process where the hub never ran) or at [ActionsGraph.install], whichever comes first. A row
 * already executing in this process is skipped by the engine's in-flight set, so recovery never runs an effect twice.
 */
internal class RecoverOnce(private val scope: CoroutineScope) {
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run(engine: () -> ActionEngine) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                engine().recover()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                try { android.util.Log.w("ActionsGraph", "recover failed: ${com.rokid.context.ai.SafeLog.describe(t)}") } catch (_: RuntimeException) { }
            }
        }
    }
}
