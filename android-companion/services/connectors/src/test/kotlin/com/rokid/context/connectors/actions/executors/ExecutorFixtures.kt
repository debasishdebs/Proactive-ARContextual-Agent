package com.rokid.context.connectors.actions.executors

import com.rokid.context.ai.contract.ShoppingLine
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.ProposalEntity

fun proposal(id: String, params: ActionParams, state: ProposalState = ProposalState.EXECUTING, createdRef: String? = null) = ProposalEntity(
    id, "ASK", "a-$id", params.type.name, ActionParams.encode(params), state.name, 0, null, createdRef, 1,
)

fun reminder(id: String, dueAtMs: Long, state: ProposalState = ProposalState.EXECUTING) =
    proposal(id, ActionParams.Reminder("Call mom", dueAtMs, null), state)

fun shopping(id: String, vararg names: String) = proposal(id, ActionParams.Shopping(names.map { ShoppingLine(it, null, null) }))

@Suppress("unused") private val keep = ActionType.REMINDER
