package com.rokid.context.memory.retrieval

import com.rokid.context.memory.retrieval.CandidateScorer.Channel
import com.rokid.context.memory.retrieval.MatchThresholds.Band
import com.rokid.context.memory.retrieval.MatchThresholds.Space
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchThresholdsTest {
    @Test fun `uncalibrated defaults never accept and never reject`() {
        assertEquals(1, MatchThresholds.VERSION) // 1 = the example photo-confirm numbers exist; the per-space bands below are still NOT SET
        for (s in Space.values()) for (c in listOf(Channel.IMAGE, Channel.TEXT, Channel.NONE)) for (m in listOf(-1000, 0, 500, 1000, null))
            assertEquals("$s $c $m", Band.BORDERLINE, MatchThresholds.band(s, c, m, 100))
    }

    @Test fun `space ids map to a space and unknown ids claim nothing`() {
        assertEquals(Space.SIGLIP2, MatchThresholds.spaceOf("siglip2-b16-224@ba1f3b0|vision_quantized+text_quantized|pooler|bilinear224+lower.eos.pad64|l2|d=768"))
        assertEquals(Space.NOMIC, MatchThresholds.spaceOf("nomic-embed-text-v1.5|mean|ln|d=256|l2+nomic-embed-vision-v1.5|cls|l2|d=768|l2"))
        assertNull(MatchThresholds.spaceOf("bge-small"))
        assertEquals(Band.BORDERLINE, MatchThresholds.band("bge-small", CandidateScorer.Result.EMPTY))
    }

    @Test fun `the constants name every space and channel and the not-set sentinels are the extremes`() {
        assertEquals(Int.MAX_VALUE, MatchThresholds.accept(Space.NOMIC, Channel.IMAGE)); assertEquals(Int.MIN_VALUE, MatchThresholds.reject(Space.SIGLIP2, Channel.TEXT))
        assertEquals(Int.MAX_VALUE, MatchThresholds.accept(Space.SIGLIP2, Channel.TEXT)); assertEquals(Int.MIN_VALUE, MatchThresholds.reject(Space.NOMIC, Channel.IMAGE))
    }
}
