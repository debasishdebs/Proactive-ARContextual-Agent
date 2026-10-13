package com.rokid.context.connectors.actions

import android.content.Context
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.executors.ActionExecutor
import com.rokid.context.connectors.actions.executors.CalendarExecutor
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.connectors.actions.executors.ShoppingExecutor
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ActionLogEntity
import com.rokid.context.memory.db.ShoppingDao
import com.rokid.context.connectors.tools.ToolConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The action engine's process-wide graph (R-62). */
interface ActionsGraphApi {
    val intake: ProposalIntake
    val engine: ActionEngine
    val settings: ActionSettingsStore
    val shopping: ShoppingDao
    /** Registered tool connectors (R-63): none in v1. */
    val tools: List<ToolConnector>
}

/**
 * One engine per process, built lazily by [get] (workers call it in processes where the hub never ran; no install needed).
 * [install] (the phone hub) supplies the HUD verbosity and runs [ActionEngine.recover] once. Before install the verbosity
 * reads MEDIUM, so a NUDGE never asks for a HUD Confirm (the conservative choice).
 */
object ActionsGraph {
    @Volatile private var instance: Impl? = null
    @Volatile private var verbosity: () -> Verbosity = { Verbosity.MEDIUM }
    @Volatile private var recovered = false

    fun get(context: Context): ActionsGraphApi = instance ?: synchronized(this) {
        instance ?: Impl(context.applicationContext).also { instance = it }
    }

    /** The graph if any caller built it (the hub does at app start); for screens that get no Context (Settings rules). */
    fun current(): ActionsGraphApi? = instance

    fun install(context: Context, verbosity: () -> Verbosity) {
        this.verbosity = verbosity
        val g = get(context) as Impl
        synchronized(this) { if (recovered) return; recovered = true }
        g.scope.launch { runCatching { g.engine.recover() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it } }
    }

    /** The production executors. No ToolConnector is registered in v1 (R-63: MCP is "Coming in v2"). */
    internal fun executors(app: Context, memory: MemoryGraph): Map<ActionType, ActionExecutor> = mapOf(
        ActionType.REMINDER to ReminderExecutor(app),
        ActionType.SHOPPING_ADD to ShoppingExecutor(memory.shopping),
        ActionType.CALENDAR_EVENT to CalendarExecutor(app.contentResolver),
    )

    private class Impl(app: Context) : ActionsGraphApi {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val memory by lazy { MemoryGraph.get(app) }
        override val settings by lazy { ActionSettingsStore(app.getSharedPreferences(ActionSettingsStore.PREFS, Context.MODE_PRIVATE)) }
        override val engine: ActionEngine by lazy {
            ActionEngine(
                memory.proposals, settings, executors(app, memory), { verbosity() }, scope, Dispatchers.IO, System::currentTimeMillis,
                audit = { id, transition, detail, now -> memory.db.actionDao().log(ActionLogEntity(ts = now, proposalId = id, transition = transition, detail = detail)) },
            )
        }
        override val shopping: ShoppingDao get() = memory.shopping
        override val tools: List<ToolConnector> = emptyList()
        override val intake: ProposalIntake = ProposalIntake { o, id, a, now -> engine.onProposed(o, id, a, now) }
    }
}
