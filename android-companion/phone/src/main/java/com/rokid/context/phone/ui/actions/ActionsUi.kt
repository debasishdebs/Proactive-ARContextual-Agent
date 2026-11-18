package com.rokid.context.phone.ui.actions

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.app.NotificationManagerCompat
import com.rokid.context.connectors.actions.ActionCopy
import com.rokid.context.connectors.actions.ActionEngine
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.Resolve
import com.rokid.context.memory.db.ActionDao
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/**
 * One proposal as a card shows it (R-62, R-14). [undone] = DECLINED by Undo (`resolvedBy = "undo:*"`). [warning] = an
 * EXECUTED action that can't reach the user as is (fixed copy, e.g. notifications off; final review I2).
 */
data class ProposalUi(
    val id: String, val type: ActionType, val summary: String, val state: ProposalState,
    val canAnswer: Boolean, val canUndo: Boolean, val failure: String?, val undone: Boolean = false, val warning: String? = null,
)

/** What a ProposalCard needs; the production one reads the engine and the database (off Main). */
interface ProposalCardSource {
    fun observe(originId: String): Flow<ProposalUi?>
    suspend fun respond(proposalId: String, yes: Boolean): Resolve
    suspend fun undo(proposalId: String): Boolean
    /** How long Undo stays offered (0 = not offered). */
    fun undoRemainingMs(proposalId: String): Long
}

/** "Enable in Settings" target for cards whose caller passes no `onEnable` (the shell may provide it). */
val LocalOpenActionsSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

object ActionsUi {
    const val PHONE = "phone"

    fun build(p: ProposalEntity, failureCode: String?, undoRemainingMs: Long, zone: ZoneId, warningCode: String? = null): ProposalUi? {
        val type = ActionParams.typeOf(p.type) ?: return null
        val state = ProposalState.entries.firstOrNull { it.name == p.state } ?: return null
        val summary = ActionParams.decode(type, p.paramsJson)?.let { ActionCopy.clip(ActionCopy.summary(it, zone)) } ?: typeLabel(type)
        return ProposalUi(
            p.id, type, summary, state,
            canAnswer = state == ProposalState.PENDING,
            canUndo = state == ProposalState.EXECUTED && undoRemainingMs > 0,
            failure = if (state == ProposalState.FAILED) ActionCopy.failureTitle(failureCode) else null,
            undone = state == ProposalState.DECLINED && p.resolvedBy?.startsWith("undo:") == true,
            warning = if (state == ProposalState.EXECUTED) ActionCopy.warningTitle(warningCode) else null,
        )
    }

    /** A tap that lost the race: what actually happened. */
    fun lateText(current: ProposalState?): String = when (current) {
        ProposalState.EXECUTING, ProposalState.EXECUTED -> "Already added"
        ProposalState.DECLINED -> "Declined"
        ProposalState.FAILED -> ActionCopy.failureTitle(null)
        else -> "Expired"
    }

    fun typeLabel(t: ActionType): String = when (t) {
        ActionType.REMINDER -> "Reminder"
        ActionType.SHOPPING_ADD -> "Shopping list"
        ActionType.CALENDAR_EVENT -> "Calendar event"
        ActionType.NOTE_ADD -> "Note"
    }
}

class GraphCardSource(
    private val engine: ActionEngine,
    private val repo: ProposalRepository,
    private val log: ActionDao,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    /** Live check: once notifications are back on, the logged NOTIFICATIONS_OFF warning no longer applies. */
    private val notificationsOn: () -> Boolean = { true },
) : ProposalCardSource {
    override fun observe(originId: String): Flow<ProposalUi?> = repo.forOrigin(originId).map { rows ->
        rows.firstOrNull()?.let { p ->
            val code = if (p.state == ProposalState.FAILED.name) log.logFor(p.id).lastOrNull { it.transition.endsWith(ProposalState.FAILED.name) }?.detail else null
            val warn = if (p.state == ProposalState.EXECUTED.name && !notificationsOn()) {
                log.logFor(p.id).lastOrNull { it.transition == ActionEngine.WARN }?.detail
            } else null
            ActionsUi.build(p, code, undoRemainingMs(p.id), zone(), warn)
        }
    }.flowOn(work)
        // Lifecycle guard: the card outlives the database for a moment when it is closed under a live composition (reset, forget-all, test teardown);
        // a closed pool ends the observation quietly (no card) instead of crashing the collector.
        .catch { e -> if (e is IllegalStateException && e.message?.contains("closed") == true) emit(null) else throw e }

    override suspend fun respond(proposalId: String, yes: Boolean): Resolve = engine.respond(proposalId, yes, ActionsUi.PHONE)
    override suspend fun undo(proposalId: String): Boolean = engine.undo(proposalId, ActionsUi.PHONE)
    override fun undoRemainingMs(proposalId: String): Long = ((engine.undoUntil(proposalId) ?: 0L) - engine.now()).coerceAtLeast(0)

    companion object {
        fun forApp(app: Context): GraphCardSource {
            val memory = MemoryGraph.get(app)
            return GraphCardSource(
                ActionsGraph.get(app).engine, memory.proposals, memory.db.actionDao(),
                notificationsOn = { NotificationManagerCompat.from(app).areNotificationsEnabled() },
            )
        }
    }
}
