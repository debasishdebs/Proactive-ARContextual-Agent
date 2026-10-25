package com.rokid.context.canvas

import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasValueJsonTest {
    @Test fun stableEncoding() {
        assertEquals("""{"kind":"photo"}""", CanvasValueJson.encode(CanvasValue.Photo))
        val ex = CanvasValue.Extraction(
            facts = listOf(Fact("A mug", "object", 0.9, listOf("t"), listOf("f1"))),
            captions = listOf(FrameCaption("f1", "A counter")), trusted = true, note = null,
        )
        assertEquals(
            """{"kind":"extraction","facts":[{"text":"A mug","type":"object","confidence":0.9,"source_frame_ids":["f1"]}],""" +
                """"captions":[{"frame_id":"f1","caption":"A counter"}],"trusted":true,"note":null}""",
            CanvasValueJson.encode(ex),
        )
        assertEquals(CanvasValueJson.encode(ex), CanvasValueJson.encode(ex.copy()))
        assertEquals(
            """{"kind":"extraction","facts":[],"captions":[],"trusted":null,"note":"unbound"}""",
            CanvasValueJson.encode(CanvasValue.Extraction(emptyList(), emptyList(), null, "unbound")),
        )
        val j = CanvasValue.Json(buildJsonObject { put("activity", "workout") })
        assertEquals("""{"kind":"json","value":{"activity":"workout"}}""", CanvasValueJson.encode(j))
        val p = CanvasValue.Preview(listOf(PreviewRow("fact", "A mug", "object", "SHORT", "OK")))
        assertEquals("""{"kind":"preview","rows":[{"kind":"fact","text":"A mug","type":"object","tier":"SHORT","review":"OK"}]}""", CanvasValueJson.encode(p))
        Json.parseToJsonElement(CanvasValueJson.encode(p)) // valid JSON
        assertEquals("x", CanvasValueJson.branchOf(NodeResult("f", null, branch = "x")))
        assertNull(CanvasValueJson.branchOf(NodeResult("f", null)))
    }

    /** A canvas Save reads the stored extraction back: what was encoded is what it decodes (tags are not stored), and any other kind or garbage is null. */
    @Test fun anExtractionDecodesBackFromItsStoredForm() {
        val ex = CanvasValue.Extraction(
            listOf(Fact("A mug", "object", 0.9, emptyList(), listOf("f1", "f2"))), listOf(FrameCaption("f1", "A counter")), trusted = true, note = "n",
        )
        assertEquals(ex, CanvasValueJson.decodeExtraction(CanvasValueJson.encode(ex)))
        assertEquals(false, CanvasValueJson.decodeExtraction(CanvasValueJson.encode(ex.copy(trusted = false)))!!.trusted)
        assertNull(CanvasValueJson.decodeExtraction(CanvasValueJson.encode(ex.copy(trusted = null)))!!.trusted)
        assertNull(CanvasValueJson.decodeExtraction(CanvasValueJson.encode(CanvasValue.Photo)))
        assertNull(CanvasValueJson.decodeExtraction("""{"kind":"json","value":{}}"""))
        assertNull(CanvasValueJson.decodeExtraction("not json")); assertNull(CanvasValueJson.decodeExtraction(null))
    }
}
