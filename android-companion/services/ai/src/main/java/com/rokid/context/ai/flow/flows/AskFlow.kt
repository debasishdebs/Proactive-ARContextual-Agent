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

/** AnswerService: retriever (current mode) -> ANSWER -> ask row, then its proposed action. */
val AskFlow = AgentFlow(
    id = AgentFlows.ASK,
    title = "Ask",
    purpose = "Answers your questions from your memories.",
    nodes = listOf(
        FlowNode("question", "Question", NodeKind.INPUT),
        step("when", "Resolve time words", *role(PromptKey.TIME_ANCHOR)),
        step("condense", "Condense the question (dated-query only)", *role(PromptKey.CONDENSE_QUERY)),
        step("search", "Search memories", Facet.Retrieval, Facet.Model(Role.EMBED)),
        step("answer", "Answer", *role(PromptKey.ANSWER)),
        FlowNode("shown", "Answer shown / spoken", NodeKind.OUTPUT),
        step("propose", "Propose action"),
    ),
    edges = listOf(
        FlowEdge("question", "when", "no date found, fallback on"),
        FlowEdge("when", "condense"),
        FlowEdge("question", "condense", "only if the pipeline has the node"),
        FlowEdge("condense", "search"),
        FlowEdge("question", "search"),
        FlowEdge("search", "answer"),
        FlowEdge("answer", "shown"),
        FlowEdge("answer", "propose", "if proposedAction"),
    ),
)
