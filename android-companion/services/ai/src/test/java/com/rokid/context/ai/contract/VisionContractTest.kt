package com.rokid.context.ai.contract

import com.rokid.context.ai.run.OutputCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Spec P2.1 provenance contract: source_frame_ids per fact, one caption per sent photo, ids only from the sent set. */
class VisionContractTest {
    private val schema = checkNotNull(DefaultSchemas.forKey(PromptKey.VISION_EXTRACT))
    private fun eval(sent: Set<String>, raw: String) =
        OutputCheck.evaluate(PromptKey.VISION_EXTRACT, schema, raw, SemanticContext(sent, LocalDateTime.of(2026, 10, 15, 10, 0)))

    private fun fact(ids: String) = """{"text":"Red mug","type":"object","confidence":0.9,"tags":["kitchen"],"source_frame_ids":$ids}"""
    private fun cap(id: String) = """{"frame_id":"$id","caption":"A kitchen counter"}"""

    @Test fun requiresSourceIdsAndCaptions() {
        // No source_frame_ids on the fact, and no captions at all: both are schema errors.
        assertFalse(eval(setOf("a"), """{"facts":[{"text":"Red mug","type":"object","confidence":0.9,"tags":[]}],"captions":[${cap("a")}]}""").schemaValid)
        assertFalse(eval(setOf("a"), """{"facts":[${fact("""["a"]""")}]}""").schemaValid)
        // An empty source list is not allowed either.
        assertFalse(eval(setOf("a"), """{"facts":[${fact("[]")}],"captions":[${cap("a")}]}""").schemaValid)
        val ok = eval(setOf("a", "b"), """{"facts":[${fact("""["a","b"]""")}],"captions":[${cap("a")},${cap("b")}]}""")
        assertTrue(ok.problems().toString(), ok.ok)
        val out = ok.output as ExtractOutput
        assertEquals(listOf("a", "b"), out.facts.single().sourceFrameIds)
        assertEquals(listOf("a", "b"), out.captions.map { it.frameId })
    }

    @Test fun unknownIdRejected() {
        val inFact = eval(setOf("a"), """{"facts":[${fact("""["a","zzz"]""")}],"captions":[${cap("a")}]}""")
        assertTrue(inFact.schemaValid && inFact.decoded)
        assertFalse(inFact.ok)
        val inCaption = eval(setOf("a"), """{"facts":[],"captions":[${cap("a")},${cap("zzz")}]}""")
        assertFalse(inCaption.ok)
    }

    @Test fun missingCaptionRejected() {
        val r = eval(setOf("a", "b"), """{"facts":[${fact("""["a"]""")}],"captions":[${cap("a")}]}""")
        assertTrue(r.schemaValid && r.decoded)
        assertFalse(r.ok)
        assertTrue(r.problems().single().startsWith("no caption for sent photos: b"))
    }

    @Test fun singlePhotoUsesSameSchema() {
        val r = eval(setOf("only"), """{"facts":[${fact("""["only"]""")}],"captions":[${cap("only")}]}""")
        assertTrue(r.problems().toString(), r.ok)
        // A photo with no facts still needs its caption, and an empty reply is not enough.
        assertTrue(eval(setOf("only"), """{"facts":[],"captions":[${cap("only")}]}""").ok)
        assertFalse(eval(setOf("only"), """{"facts":[],"captions":[]}""").ok)
        // EXPLICIT_REMEMBER keeps the plain facts schema.
        assertFalse(DefaultSchemas.text(PromptKey.EXPLICIT_REMEMBER)!!.contains("source_frame_ids"))
    }
}
