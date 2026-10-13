package com.rokid.context.connectors.actions.executors

import com.rokid.context.memory.db.ProposalEntity

/**
 * Performs one proposal's side effect (R-62). [execute] must be idempotent per proposalId: the engine re-runs it after a
 * crash between EXECUTING and EXECUTED. [undo] reverses it (also idempotent); false = nothing could be undone.
 */
interface ActionExecutor {
    suspend fun execute(p: ProposalEntity): ExecResult
    suspend fun undo(p: ProposalEntity): Boolean
}

sealed interface ExecResult {
    /**
     * The effect landed. [warning] = it landed but cannot reach the user as is (e.g. NOTIFICATIONS_OFF): the proposal
     * still ends EXECUTED, and the engine logs the code so the card can say so (final review I2).
     */
    data class Done(val createdRef: String, val warning: FailCode? = null) : ExecResult
    data class Failed(val code: FailCode) : ExecResult
}

/** Fixed failure codes: stored in action_log and mapped to copy by ActionCopy (never provider or exception text). */
enum class FailCode { NO_PERMISSION, NO_CALENDAR, NOTIFICATIONS_OFF, UNKNOWN }
