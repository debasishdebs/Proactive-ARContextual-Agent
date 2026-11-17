package io.github.pca.nexusport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T3 golden tests: one per kind of the plan's mapping table, the 1024 cap, the TTL clamp and the action set. */
class HudMapperTest {
    @Test fun nudgeBecomesNoticeWithDismissMarkMore() {
        val c = assertNotNull(HudMapper.map(nudge())).command
        assertEquals(Slot.NOTICE, c.slot)
        assertEquals(listOf("dismiss", "mark", "more"), c.actions.map { it.id })
        assertEquals(listOf("Dismiss", "Mark", "More"), c.actions.map { it.label })
        assertEquals("Title", c.title)
        assertEquals("body", c.text)
        assertEquals(8_000, c.ttlMs)
    }

    @Test fun placeCardMapsLikeANudgeAndWakeIsPassedThrough() {
        val c = assertNotNull(HudMapper.map(HudEvent.PlaceCard("p", "Cafe", "A card", setOf("frame:1"), 5_000, wake = true))).command
        assertEquals(Slot.NOTICE, c.slot); assertTrue(c.wake); assertEquals(3, c.actions.size)
    }

    @Test fun askAnswerIsANoticeWithCitationFooterAndOptionalSpeech() {
        val m = assertNotNull(HudMapper.map(answer(speak = true)))
        assertEquals("Answer", m.command.title)
        assertEquals("The answer\nSources: Mon note", m.command.text)
        assertEquals(listOf("dismiss", "more"), m.command.actions.map { it.id })
        assertEquals("The answer", m.speech)
        assertNull(HudMapper.map(answer(speak = false))!!.speech)
    }

    @Test fun markConfirmUsesReasonTokensNeverThePlaceName() {
        assertEquals("Not saved: pause zone", HudMapper.map(HudEvent.MarkConfirm("m", "pause_zone", emptySet(), 5_000))!!.command.text)
        assertEquals("Marked", HudMapper.map(HudEvent.MarkConfirm("m", "Cafe Mocha, MG Road", emptySet(), 5_000))!!.command.text)
    }

    @Test fun captureStateIsAContentFreePin() {
        for (s in HudEvent.CaptureState.State.entries) {
            val c = HudMapper.map(HudEvent.CaptureState(s))!!.command
            assertEquals(Slot.PIN, c.slot)
            assertTrue(c.title.length <= NexusLimits.PIN_TITLE_CHARS && c.text.length <= NexusLimits.PIN_LINE_CHARS)
            assertEquals(NexusLimits.PIN_TTL_MAX_MS, c.ttlMs)
        }
        assertEquals("Paused", HudMapper.map(HudEvent.CaptureState(HudEvent.CaptureState.State.PAUSED))!!.command.text)
    }

    @Test fun activityAndSurfaceKindsAreCutFrom20() {
        assertNull(HudMapper.map(HudEvent.Activity("a", "t", emptySet())))
        assertNull(HudMapper.map(HudEvent.SurfaceCard("s", "t", emptySet())))
    }

    @Test fun textIsCutTo1024CharsAndSixteenLinesIncludingTheFooter() {
        val long = "x".repeat(5_000)
        val c = HudMapper.map(HudEvent.AskAnswer("a", long, listOf("A note"), setOf("m"), 5_000))!!.command
        assertTrue(c.text.length <= NexusLimits.NOTICE_TEXT_CHARS, "len=${c.text.length}")
        assertTrue(c.text.endsWith("Sources: A note"))
        val lines = HudMapper.map(HudEvent.AskAnswer("a", (1..40).joinToString("\n") { "l$it" }, listOf("n"), setOf("m"), 5_000))!!.command
        assertTrue(lines.text.lines().size <= NexusLimits.NOTICE_LINES)
        assertTrue(HudMapper.map(nudge(body = "y".repeat(2_000)))!!.command.text.length <= NexusLimits.NOTICE_TEXT_CHARS)
    }

    @Test fun cuttingNeverSplitsASurrogatePair() {
        val s = "a" + "😀".repeat(600)
        val t = HudMapper.clipText(s, 1024)
        assertTrue(t.length <= 1024)
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (ch.isHighSurrogate()) { assertTrue(i + 1 < t.length && t[i + 1].isLowSurrogate(), "unpaired high surrogate at $i"); i++ }
            else assertTrue(!ch.isLowSurrogate(), "unpaired low surrogate at $i")
            i++
        }
    }

    @Test fun ttlIsClampedToNexusRange() {
        assertEquals(NexusLimits.NOTICE_TTL_MIN_MS, HudMapper.map(nudge(ttl = 100))!!.command.ttlMs)
        assertEquals(NexusLimits.NOTICE_TTL_MAX_MS, HudMapper.map(nudge(ttl = 600_000))!!.command.ttlMs)
        assertEquals(20_000, HudMapper.map(nudge(ttl = 20_000))!!.command.ttlMs)
    }

    @Test fun titleAndLabelsFitNexus() {
        val c = HudMapper.map(HudEvent.Nudge("n", "T".repeat(100), "b", setOf("m"), 5_000))!!.command
        assertTrue(c.title.length <= NexusLimits.NOTICE_TITLE_CHARS)
        assertTrue(c.actions.all { it.label.length <= NexusLimits.ACTION_LABEL_CHARS })
        assertTrue(c.actions.size <= NexusLimits.MAX_ACTIONS)
    }
}
