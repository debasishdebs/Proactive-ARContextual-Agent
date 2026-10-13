package com.rokid.context.ai.flow.flows

import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.AgentFlow
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowEdge
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.ai.flow.NodeKind
import com.rokid.context.ai.flow.role
import com.rokid.context.ai.flow.step

/** QueueDrainWorker (batched summarization, priority reprocess, critic review, explicit write) and ResummarizeWorker. */
val CaptureFlow = AgentFlow(
    id = AgentFlows.CAPTURE,
    title = "Capture",
    purpose = "Turns glasses photos and things you say into memories.",
    nodes = listOf(
        FlowNode("photo", "Photo", NodeKind.INPUT),
        step("extract", "Facts from frames", *role(PromptKey.VISION_EXTRACT)),
        step("confidence", "Confidence check"),
        step("critic", "Critic", *role(PromptKey.CRITIC)),
        step("save", "Save memory"),
        step("save_review", "Save memory (needs review)"),
        step("reprocess", "Reprocess", *role(PromptKey.SUMMARIZE)),
        step("embed", "Embed", Facet.Model(Role.EMBED)),
        FlowNode("memory", "Memory", NodeKind.OUTPUT),
        FlowNode("to_nudge", "\u2192 Nudge", NodeKind.LINK, linkTo = AgentFlows.NUDGE),
        FlowNode("remember_in", "\"Remember this\"", NodeKind.INPUT),
        step("remember", "Remember this", *role(PromptKey.EXPLICIT_REMEMBER)),
        FlowNode("thumbs_in", "\uD83D\uDC4E on an answer", NodeKind.INPUT),
        step("critic_review", "Critic review", *role(PromptKey.CRITIC)),
        FlowNode("forget_in", "Forget", NodeKind.INPUT),
        step("resummarize", "Re-summarise derived memories", *role(PromptKey.SUMMARIZE)),
    ),
    edges = listOf(
        FlowEdge("photo", "extract"),
        FlowEdge("extract", "confidence"),
        FlowEdge("confidence", "save", "trusted"),
        FlowEdge("confidence", "critic", "low confidence"),
        FlowEdge("critic", "save", "pass"),
        FlowEdge("critic", "save_review", "fail / unbound"),
        FlowEdge("save_review", "reprocess"),
        FlowEdge("reprocess", "save"),
        FlowEdge("save", "embed"),
        FlowEdge("embed", "memory"),
        FlowEdge("save", "to_nudge", "after a trusted pass or reprocess"),
        FlowEdge("remember_in", "remember"),
        FlowEdge("remember", "save"),
        FlowEdge("thumbs_in", "critic_review"),
        FlowEdge("critic_review", "memory", "pass \u2192 OK / fail \u2192 stays in review"),
        FlowEdge("forget_in", "resummarize"),
        FlowEdge("resummarize", "save"),
    ),
)
