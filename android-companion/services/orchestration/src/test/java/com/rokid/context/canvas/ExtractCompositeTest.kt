package com.rokid.context.canvas

import com.rokid.context.ai.canvas.ExtractComposite
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.orchestration.ExtractSave
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden: the exposed `batch` shown with its default behaves exactly like a saved pipeline that has no `batch` (what `Facts from frames` does today). */
class ExtractCompositeTest {
    private val e = VisionExtractExecutor
    private fun node(params: JsonObject) = PipelineNode("x", NodeTypes.VISION_EXTRACT, "Facts", params)
    private val absent = node(JsonObject(emptyMap()))
    private val explicit = node(buildJsonObject { put("batch", ExtractComposite.DEFAULT_BATCH) })

    @Test fun theDefaultIsWhatAnAbsentBatchDoes() {
        assertEquals(ExtractComposite.DEFAULT_BATCH, e.chunkSize(absent))
        assertEquals(1, ExtractComposite.DEFAULT_BATCH)
    }

    @Test fun writingTheDefaultChangesNothingForAnyItemCount() {
        assertEquals(e.chunkSize(absent), e.chunkSize(explicit))
        for (n in 0..40) {
            assertEquals("nominal $n", e.nominalCalls(absent, n), e.nominalCalls(explicit, n))
            assertEquals("max $n", e.maxCalls(absent, n), e.maxCalls(explicit, n))
        }
    }

    @Test fun theLargestExposedBatchIsTheSavePathsOwn() {
        assertEquals(ExtractSave.MAX_BATCH, e.chunkSize(node(buildJsonObject { put("batch", ExtractSave.MAX_BATCH) })))
        assertEquals(ExtractSave.MAX_BATCH, e.chunkSize(node(buildJsonObject { put("batch", 99) })))
        assertEquals(1, e.chunkSize(node(buildJsonObject { put("batch", JsonPrimitive("two")) })))
    }
}
