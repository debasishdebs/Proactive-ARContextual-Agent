package com.rokid.context.ai.flow

import com.rokid.context.ai.Role
import com.rokid.context.ai.flow.flows.AskFlow
import com.rokid.context.ai.flow.flows.CaptureFlow
import com.rokid.context.ai.flow.flows.NudgeFlow

/** The registry. Adding an agent means adding one flow file and listing it in [all]. */
object AgentFlows {
    const val CAPTURE = "capture"
    const val ASK = "ask"
    const val NUDGE = "nudge"

    val all: List<AgentFlow> = listOf(CaptureFlow, AskFlow, NudgeFlow)

    fun byId(id: String): AgentFlow? = all.firstOrNull { it.id == id }

    /** Every (flowId, node) that has Facet.Model(role) - for "shared by N steps". */
    fun nodesUsing(role: Role): List<Pair<AgentFlow, FlowNode>> =
        all.flatMap { f -> f.nodes.filter { Facet.Model(role) in it.facets }.map { f to it } }
}
