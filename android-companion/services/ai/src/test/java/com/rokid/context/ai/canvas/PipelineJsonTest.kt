package com.rokid.context.ai.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineJsonTest {
    private val sample = Pipeline(
        schema = 1, id = "live-extract", name = "Live extraction",
        nodes = listOf(
            PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "Photo", JsonObject(emptyMap())),
            PipelineNode("extract", NodeTypes.VISION_EXTRACT, "Facts", buildJsonObject {
                put("batch", 3)
                put("prompt", buildJsonObject { put("source", "draft"); put("template", "T"); put("schema", JsonPrimitive(null as String?)) })
            }),
            PipelineNode("trust", NodeTypes.LOCAL_CRITIC, "Trust", JsonObject(emptyMap())),
        ),
        edges = listOf(PipelineEdge("photos", "extract"), PipelineEdge("extract", "trust"), PipelineEdge("trust", "extract", "unsure")),
    )

    @Test fun roundTripKeepsEverything() {
        assertEquals(sample, PipelineJson.decode(PipelineJson.encode(sample)).getOrThrow())
        assertEquals(sample, PipelineJson.decode(PipelineJson.encode(sample, pretty = true)).getOrThrow())
    }

    @Test fun decodeRejectsDeepNestingWithoutOverflow() {
        val deep = "{\"schema\":1,\"id\":\"a\",\"name\":\"b\",\"nodes\":[],\"edges\":[],\"x\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
        assertTrue(PipelineJson.decode(deep).isFailure)
        val mid = "{\"schema\":1,\"id\":\"a\",\"name\":\"b\",\"nodes\":[],\"edges\":[],\"x\":" + "[".repeat(40) + "]".repeat(40) + "}"
        assertEquals("too deep", PipelineJson.decode(mid).exceptionOrNull()?.message)
        assertTrue(PipelineJson.decode(PipelineJson.encode(sample)).isSuccess)
    }

    @Test fun decodeRejectsOversize() {
        val big = PipelineJson.encode(sample).dropLast(1) + ",\"pad\":\"" + "x".repeat(PipelineJson.MAX_BYTES) + "\"}"
        assertEquals("too large", PipelineJson.decode(big).exceptionOrNull()?.message)
        assertEquals("not JSON", PipelineJson.decode("{nope").exceptionOrNull()?.message)
        assertEquals("missing nodes", PipelineJson.decode("{\"schema\":1,\"id\":\"a\",\"name\":\"n\",\"edges\":[]}").exceptionOrNull()?.message)
    }

    @Test fun decodeRejectsWrongSchemaVersion() {
        val text = PipelineJson.encode(sample).replace("\"schema\":1", "\"schema\":3")
        assertEquals("unsupported schema", PipelineJson.decode(text).exceptionOrNull()?.message)
    }

    @Test fun canonicalIsKeyOrderIndependent() {
        val a = "{\"schema\":1,\"id\":\"p\",\"name\":\"n\",\"nodes\":[{\"id\":\"photos\",\"type\":\"input.photos\",\"title\":\"t\",\"params\":{\"a\":1,\"b\":{\"x\":1,\"y\":2}}}],\"edges\":[]}"
        val b = "{ \"edges\":[], \"name\":\"n\", \"nodes\":[{\"params\":{\"b\":{\"y\":2,\"x\":1},\"a\":1},\"title\":\"t\",\"type\":\"input.photos\",\"id\":\"photos\"}], \"id\":\"p\", \"schema\":1 }"
        val pa = PipelineJson.decode(a).getOrThrow()
        val pb = PipelineJson.decode(b).getOrThrow()
        assertEquals(PipelineJson.canonical(pa), PipelineJson.canonical(pb))
        assertTrue(!PipelineJson.canonical(pa).contains(" "))
        assertEquals("{\"edges\":[],\"id\":\"p\",\"name\":\"n\",\"nodes\":[{\"id\":\"photos\",\"params\":{\"a\":1,\"b\":{\"x\":1,\"y\":2}},\"title\":\"t\",\"type\":\"input.photos\"}],\"schema\":1}", PipelineJson.canonical(pa))
    }

    @Test fun promptSourceLiveAndDraft() {
        fun node(params: JsonObject) = PipelineNode("n", NodeTypes.VISION_EXTRACT, "t", params)
        assertNull(node(JsonObject(emptyMap())).promptSource())
        assertEquals(PromptSource.Live, node(buildJsonObject { put("prompt", buildJsonObject { put("source", "live") }) }).promptSource())
        assertEquals(
            PromptSource.Draft("T", "{}"),
            node(buildJsonObject { put("prompt", buildJsonObject { put("source", "draft"); put("template", "T"); put("schema", "{}") }) }).promptSource(),
        )
        assertEquals(
            PromptSource.Draft("T", null),
            node(buildJsonObject { put("prompt", buildJsonObject { put("source", "draft"); put("template", "T"); put("schema", JsonPrimitive(null as String?)) }) }).promptSource(),
        )
    }
}
