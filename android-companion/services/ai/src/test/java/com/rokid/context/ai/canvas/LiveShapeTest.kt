package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveShapeTest {
    private fun live() = DefaultPipelines.liveExtract()
    private fun edit(p: Pipeline, id: String, params: kotlinx.serialization.json.JsonObject) =
        p.copy(nodes = p.nodes.map { if (it.id == id) it.copy(params = params) else it })

    @Test fun liveExtractMatches() = assertTrue(LiveShape.matches(live()))

    @Test fun promptEditStillMatches() {
        val p = edit(live(), "extract", buildJsonObject {
            put("batch", 3)
            putJsonObject("prompt") { put("source", "draft"); put("template", "T"); put("schema", JsonPrimitive(null as String?)) }
        })
        assertTrue(LiveShape.matches(p))
    }

    @Test fun batchChangeBreaksShape() {
        assertFalse(LiveShape.matches(edit(live(), "extract", buildJsonObject { put("batch", 2); putJsonObject("prompt") { put("source", "live") } })))
    }

    @Test fun extraNodeBreaksShape() {
        val p = live()
        assertFalse(LiveShape.matches(p.copy(nodes = p.nodes + PipelineNode("more", NodeTypes.MEMORY_WRITE, "More", p.nodes[0].params))))
        assertFalse(LiveShape.matches(p.copy(edges = p.edges.dropLast(1))))
    }

    @Test fun intentDraftDoesNotMatch() = assertFalse(LiveShape.matches(DefaultPipelines.intentDraft("S")))

    @Test fun draftPromptNodesListsOnlyDrafts() {
        assertEquals(emptyList<String>(), LiveShape.draftPromptNodes(live()).map { it.id })
        val p = edit(live(), "critic", buildJsonObject { putJsonObject("prompt") { put("source", "draft"); put("template", "T") } })
        assertEquals(listOf("critic"), LiveShape.draftPromptNodes(p).map { it.id })
    }
}
