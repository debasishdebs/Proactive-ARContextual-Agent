package com.rokid.context.ai.flow.flows

import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.ai.flow.role
import com.rokid.context.ai.flow.step

/**
 * NudgeTrigger.afterSummarizerPass -> NudgeWorker -> NudgeEngine. The proposal is made only after a nudge is shown
 * (NudgeEngine returns early when !shouldNudge), so the propose edge carries both conditions.
 */
val NudgeFlow = AgentFlow(
    id = AgentFlows.NUDGE,
    title = "Nudge",
    purpose = "Suggests something useful after new memories land.",
    nodes = listOf(
        FlowNode("trigger", "After summarizer pass", NodeKind.INPUT),
        step("recent", "Recent memories"),
        step("nudge", "Nudge", *role(PromptKey.NUDGE)),
        FlowNode("shown", "Nudge shown", NodeKind.OUTPUT),
        step("propose", "Propose action"),
    ),
    edges = listOf(
        FlowEdge("trigger", "recent"),
        FlowEdge("recent", "nudge"),
        FlowEdge("nudge", "shown", "if shouldNudge"),
        FlowEdge("nudge", "propose", "if shouldNudge and proposedAction"),
    ),
)
