package com.rokid.context.connectors

import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.memory.ProposalOrigin

/** Where a model-proposed action enters the action engine (spec §4.4, R-62). Stream B implements it. */
fun interface ProposalIntake {
    /** After the ask/nudge row is committed. Returns the proposalId, or null if nothing was stored. Never throws. */
    suspend fun onProposed(origin: ProposalOrigin, originId: String, action: ProposedAction, nowMs: Long): String?

    companion object { val None = ProposalIntake { _, _, _, _ -> null } }
}
