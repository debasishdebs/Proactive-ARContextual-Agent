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
    data class Done(val createdRef: String) : ExecResult
    data class Failed(val code: FailCode) : ExecResult
}

/** Fixed failure codes: stored in action_log and mapped to copy by ActionCopy (never provider or exception text). */
enum class FailCode { NO_PERMISSION, NO_CALENDAR, NOTIFICATIONS_OFF, UNKNOWN }
