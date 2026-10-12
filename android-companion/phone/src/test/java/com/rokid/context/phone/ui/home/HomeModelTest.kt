package com.rokid.context.phone.ui.home

import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModelTest {
    @Test fun feedMergesAsksAndNudgesNewestFirstWithStableTies() {
        val feed = Feed.build(
            asks = listOf(Fixtures.ask("a1", Fixtures.at(TODAY, 8, 15)), Fixtures.ask("a2", Fixtures.at(TODAY, 9, 41))),
            nudges = listOf(Fixtures.nudge("n1", Fixtures.at(TODAY, 9, 41)), Fixtures.nudge("n0", Fixtures.at(TODAY.minusDays(2), 7))),
            today = TODAY, zone = ZONE,
        )
        assertEquals(listOf("a2", "n1", "a1", "n0"), feed.map { it.id })
        assertEquals("09:41", feed[0].timeLabel)
        assertEquals("10 Oct", feed[3].timeLabel)
        assertEquals(2, Feed.build(feed.indices.map { Fixtures.ask("x$it", it.toLong()) }, emptyList(), TODAY, ZONE, limit = 2).size)
    }

    @Test fun suggestionsUseRecentTagsThenFallbacks() {
        val recent = listOf(
            Fixtures.memory("m1", "Milk", 3, tags = "milk, fridge"),
            Fixtures.memory("m2", "Keys", 2, tags = "Milk,x,😀emoji, a-b"),
        )
        assertEquals(listOf("What about milk?", "What about fridge?", SuggestedQuestions.FALLBACKS[0]), SuggestedQuestions.from(recent))
        assertEquals(SuggestedQuestions.FALLBACKS, SuggestedQuestions.from(emptyList()))
    }

    @Test fun hindiTagsBecomeSuggestions() {
        assertEquals("What about दूध?", SuggestedQuestions.from(listOf(Fixtures.memory("m1", "दूध खत्म", 1, tags = "दूध")))[0])
    }
}
