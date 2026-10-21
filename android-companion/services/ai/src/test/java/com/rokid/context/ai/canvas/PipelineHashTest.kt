package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PipelineHashTest {
    private fun pipe(template: String) = Pipeline(
        1, "p", "P",
        listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", JsonObject(emptyMap())),
            PipelineNode("extract", NodeTypes.VISION_EXTRACT, "E", buildJsonObject {
                put("prompt", buildJsonObject { put("source", "draft"); put("template", template) })
            }),
        ),
        listOf(PipelineEdge("photos", "extract")),
    )

    @Test fun sameContentSameHash() {
        val h = PipelineHash.of(pipe("a"))
        assertEquals(h, PipelineHash.of(PipelineJson.decode(PipelineJson.encode(pipe("a"), pretty = true)).getOrThrow()))
        assertEquals(64, h.length)
        assertEquals(h.take(12), PipelineHash.short(h))
    }

    @Test fun promptEditChangesHash() {
        assertNotEquals(PipelineHash.of(pipe("a")), PipelineHash.of(pipe("b")))
    }
}
