package com.rokid.context.phone.ui.banner

import com.rokid.context.memory.IndexHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gaps G6: the user sees why on-device search is paused, in the existing index banner slot (no raw text). */
class BatteryPauseBannerTest {
    @Test fun bannerShowsBatteryPauseReason() {
        val state = IndexState.of(IndexHealth.NONE, unstable = false, embedBound = true, batteryPaused = true)
        assertEquals(IndexState.BATTERY_PAUSED, state)
        val b = BannerResolver.resolve(BannerInputs(index = state))!!
        assertEquals(BannerKind.INDEX, b.kind)
        assertEquals("On-device search paused to save battery", b.title)
        assertEquals(null, b.action)
        assertEquals("unstable still wins", IndexState.UNSTABLE, IndexState.of(IndexHealth.NONE, unstable = true, embedBound = true, batteryPaused = true))
        assertEquals("no EMBED model: nothing paused, semantic search is off instead", IndexState.EMBED_NOT_SET, IndexState.of(IndexHealth.NONE, unstable = false, embedBound = false, batteryPaused = true))
        assertEquals(IndexState.RUNNING, IndexState.of(IndexHealth.RUNNING, unstable = false, embedBound = true, batteryPaused = false))
    }

    /** 2026-11-08: with no Embeddings model the app said nothing while every ask fell back to keywords; now Home says so and links the fix. */
    @Test fun noEmbeddingsModelIsShownUnlessLexicalWasChosen() {
        val state = IndexState.of(IndexHealth.NONE, unstable = false, embedBound = false, batteryPaused = false)
        assertEquals(IndexState.EMBED_NOT_SET, state)
        val b = BannerResolver.resolve(BannerInputs(index = state))!!
        assertEquals(BannerKind.INDEX, b.kind)
        assertEquals("Semantic search is off · no Embeddings model", b.title)
        assertEquals(BannerAction.FIX_AI, b.action)
        assertEquals("Lexical is the user's choice", IndexState.NONE, IndexState.of(IndexHealth.NONE, unstable = false, embedBound = false, batteryPaused = false, semanticWanted = false))
        assertEquals(IndexState.NONE, IndexState.of(IndexHealth.NONE, unstable = false, embedBound = true, batteryPaused = false))
    }
}
