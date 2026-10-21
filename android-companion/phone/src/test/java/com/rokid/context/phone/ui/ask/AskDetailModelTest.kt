package com.rokid.context.phone.ui.ask

import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.ZONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskDetailModelTest {
    private val ask = Fixtures.ask("a1", Fixtures.at(Fixtures.TODAY, 9, 41), sources = """["m2","m1","gone","m3"]""")
    private val memories = listOf(
        Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1", confidence = 0.71),
        Fixtures.memory("m2", "Milk low", 2, confidence = 0.92),
        Fixtures.memory("m3", "Hidden", 3, review = ReviewState.INVALIDATED),
    )
    private val frames = listOf(Fixtures.frame("f1", 1, path = "/frames/f1.jpg"))

    @Test fun sourcesFollowCitationOrderAndCountMissing() {
        val s = AskDetailModel.build(ask, memories, frames, emptyList(), ZONE)
        assertTrue(s.found)
        assertEquals(listOf("m2", "m1"), s.sources.map { it.memoryId })
        assertEquals("/frames/f1.jpg", s.sources[1].framePath)
        assertEquals(null, s.sources[0].framePath)
        assertEquals(2, s.missingSources) // "gone" and the invalidated m3
        assertEquals("Mon 12 Oct · 09:41", s.timeLabel)
    }

    @Test fun redactedAskHidesSources() {
        val s = AskDetailModel.build(ask.copy(redacted = true, answer = "Removed — a source was forgotten"), memories, frames, emptyList(), ZONE)
        assertTrue(s.redacted)
        assertTrue(s.sources.isEmpty())
        assertEquals(0, s.missingSources)
        assertEquals("Removed — a source was forgotten", s.answer)
    }

    @Test fun malformedSourcesGiveNoCards() {
        val s = AskDetailModel.build(ask.copy(sourcesJson = "{oops"), memories, frames, emptyList(), ZONE)
        assertTrue(s.sources.isEmpty()); assertEquals(0, s.missingSources)
    }

    @Test fun proposalStatesReadAsPlainWords() {
        assertEquals(ProposalLine("Add to shopping list", "Waiting for your answer"), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1")))
        assertEquals(ProposalLine("Reminder", "Added"), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", type = "REMINDER", state = "EXECUTED")))
        assertEquals("Turned off in Settings", AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", state = "SUPPRESSED")).state)
        assertEquals(ProposalLine("Suggested action", ""), AskDetailModel.proposalLine(Fixtures.proposal("p", "a1", type = "??", state = "??")))
    }

    @Test fun frameSourceRendersPhotoCard() {
        val a = ask.copy(sourcesJson = """["frame:f1","m2"]""")
        val s = AskDetailModel.build(a, memories, frames, emptyList(), ZONE, photoFrames = mapOf("f1" to frames[0]))
        assertEquals(listOf("frame:f1", "m2"), s.sources.map { it.memoryId })
        val card = s.sources[0]
        assertEquals("Photo", card.text); assertEquals(1.0, card.confidence, 0.0); assertEquals("/frames/f1.jpg", card.framePath)
        assertEquals(false, card.expired); assertEquals("f1", card.photoFrameId)
        assertEquals(0, s.missingSources)
    }

    @Test fun expiredFrameSourceShowsPlaceholderAndIsNotMissing() {
        val a = ask.copy(sourcesJson = """["frame:f9"]""")
        val s = AskDetailModel.build(a, memories, frames, emptyList(), ZONE, photoFrames = mapOf("f9" to null))
        assertEquals(1, s.sources.size)
        assertEquals(true, s.sources[0].expired); assertEquals(null, s.sources[0].framePath); assertEquals(null, s.sources[0].photoFrameId)
        assertEquals(0, s.missingSources)
        val unknown = AskDetailModel.build(a, memories, frames, emptyList(), ZONE) // frame never looked up: still a photo cite, shown expired
        assertEquals(true, unknown.sources.single().expired)
        assertEquals(0, unknown.missingSources)
    }
}
