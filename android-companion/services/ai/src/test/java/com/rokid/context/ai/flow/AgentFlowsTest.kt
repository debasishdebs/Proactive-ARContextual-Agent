package com.rokid.context.ai.flow

import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFlowsTest {
    private val facets = AgentFlows.all.flatMap { f -> f.nodes.flatMap { it.facets } }

    @Test fun everyRoleAndRolePromptAppearsInSomeFlow() {
        Role.entries.forEach { r -> assertTrue("Role $r has no Model facet", Facet.Model(r) in facets) }
        PromptKey.entries.filter { it.role != null }.forEach { k -> assertTrue("PromptKey $k has no Prompt facet", Facet.Prompt(k) in facets) }
    }

    @Test fun nodeIdsAreUniquePerFlow() {
        AgentFlows.all.forEach { f -> assertEquals("duplicate node id in ${f.id}", f.nodes.size, f.nodes.map { it.id }.toSet().size) }
    }

    @Test fun flowIdsAreUnique() {
        assertEquals(AgentFlows.all.size, AgentFlows.all.map { it.id }.toSet().size)
    }

    @Test fun edgesReferenceNodesOfTheirFlow() {
        AgentFlows.all.forEach { f ->
            val ids = f.nodes.map { it.id }.toSet()
            f.edges.forEach { e -> assertTrue("${f.id}: edge ${e.from}->${e.to} names a missing node", e.from in ids && e.to in ids) }
        }
    }

    @Test fun flowsAreAcyclic() {
        AgentFlows.all.forEach { f ->
            val indeg = f.nodes.associate { it.id to 0 }.toMutableMap()
            f.edges.forEach { indeg[it.to] = indeg.getValue(it.to) + 1 }
            val ready = ArrayDeque(indeg.filterValues { it == 0 }.keys)
            var seen = 0
            while (ready.isNotEmpty()) {
                val n = ready.removeFirst()
                seen++
                f.edges.filter { it.from == n }.forEach { e ->
                    indeg[e.to] = indeg.getValue(e.to) - 1
                    if (indeg.getValue(e.to) == 0) ready.addLast(e.to)
                }
            }
            assertEquals("${f.id} has a cycle", f.nodes.size, seen)
        }
    }

    @Test fun everyFlowHasAnInput() {
        AgentFlows.all.forEach { f -> assertTrue("${f.id} has no INPUT", f.nodes.any { it.kind == NodeKind.INPUT }) }
    }

    @Test fun linkNodesNameARegisteredFlow() {
        AgentFlows.all.forEach { f ->
            f.nodes.filter { it.kind == NodeKind.LINK }.forEach { n ->
                assertNotNull("${f.id}/${n.id} links to an unknown flow ${n.linkTo}", n.linkTo?.let(AgentFlows::byId))
            }
        }
    }

    @Test fun nonLinkNodesHaveNoLinkTo() {
        AgentFlows.all.forEach { f -> f.nodes.filter { it.kind != NodeKind.LINK }.forEach { assertNull("${f.id}/${it.id}", it.linkTo) } }
    }

    @Test fun byIdReturnsNullForUnknown() {
        assertNull(AgentFlows.byId("no-such-flow"))
        assertEquals(AgentFlows.ASK, AgentFlows.byId(AgentFlows.ASK)?.id)
        assertEquals(listOf(AgentFlows.CAPTURE, AgentFlows.ASK, AgentFlows.NUDGE), AgentFlows.all.map { it.id })
    }

    @Test fun nodesUsingEmbedListsEmbedAndSearchMemories() {
        val used = AgentFlows.nodesUsing(Role.EMBED).map { (f, n) -> f.id to n.title }
        assertEquals(listOf(AgentFlows.CAPTURE to "Embed", AgentFlows.ASK to "Search memories"), used)
    }

    @Test fun nodesUsingPromptKeyListsEveryStepSharingThePrompt() {
        val titles = { k: PromptKey -> AgentFlows.nodesUsing(k).map { (f, n) -> f.id to n.title } }
        assertEquals(listOf(AgentFlows.CAPTURE to "Critic", AgentFlows.CAPTURE to "Critic review"), titles(PromptKey.CRITIC))
        assertEquals(listOf(AgentFlows.CAPTURE to "Reprocess", AgentFlows.CAPTURE to "Re-summarise derived memories"), titles(PromptKey.SUMMARIZE))
        assertEquals(listOf(AgentFlows.ASK to "Answer"), titles(PromptKey.ANSWER))
    }

    @Test fun aiStepsAreTheNodesWithFacets() {
        val ask = AgentFlows.byId(AgentFlows.ASK)!!
        assertEquals(listOf("when", "search", "answer"), ask.nodes.filter { it.isAiStep }.map { it.id })
        assertEquals(listOf(Facet.Retrieval, Facet.Model(Role.EMBED)), ask.nodes.first { it.id == "search" }.facets)
    }
}
