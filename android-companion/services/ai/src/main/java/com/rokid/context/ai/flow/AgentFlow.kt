package com.rokid.context.ai.flow

import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey

/** One agent pipeline drawn as a DAG (agent flows spec section 2). Read-only: it describes the code, it does not run it. */
data class AgentFlow(val id: String, val title: String, val purpose: String, val nodes: List<FlowNode>, val edges: List<FlowEdge>)

/** [label] carries the condition of a conditional edge, e.g. "if shouldNudge". */
data class FlowEdge(val from: String, val to: String, val label: String? = null)

/** [linkTo] names another registered flow; only [NodeKind.LINK] nodes set it. */
data class FlowNode(val id: String, val title: String, val kind: NodeKind, val facets: List<Facet> = emptyList(), val linkTo: String? = null) {
    /** An "AI step" (bold card) has at least one configurable facet. */
    val isAiStep: Boolean get() = facets.isNotEmpty()
}

/** INPUT/OUTPUT/LINK and facet-less STEPs render as muted pills. */
enum class NodeKind { INPUT, STEP, OUTPUT, LINK }

sealed interface Facet {
    /** LLM prompt: editor, test bed, versions. */
    data class Prompt(val key: PromptKey) : Facet
    /** Endpoint/model/temperature/fallback binding of a role (shared by every step that uses the role). */
    data class Model(val role: Role) : Facet
    /** Lexical/semantic/hybrid + fusion settings (app-wide). */
    data object Retrieval : Facet
    /** A future ML model: an editor registered by id in the phone. */
    data class Custom(val editorId: String) : Facet
}

internal fun step(id: String, title: String, vararg facets: Facet) = FlowNode(id, title, NodeKind.STEP, facets.toList())
internal fun role(key: PromptKey): Array<Facet> = arrayOf(Facet.Prompt(key), Facet.Model(key.role ?: error("$key has no role")))
