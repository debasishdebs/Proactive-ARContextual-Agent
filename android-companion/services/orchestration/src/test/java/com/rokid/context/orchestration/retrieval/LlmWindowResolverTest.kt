package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.contract.TimeAnchorOutput
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval-quality spec section 3, the LLM fallback: a fake model call stands in for the runner. No cue = no call; a bad reply, a bad range, a timeout or an exception = no window;
 * one call per (question, day); `kind=event` anchors on the memories' capture times.
 */
class LlmWindowResolverTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = ms("2026-11-16T12:04:00")
    private fun out(kind: String, from: String = "", to: String = "", event: String = "", label: String = "") = TimeAnchorOutput(kind, from, to, event, label)

    private fun resolver(timeoutMs: Long = 3_000, anchor: (String) -> Pair<Long, Long>? = { null }, reply: suspend (String) -> TimeAnchorOutput?) =
        LlmWindowResolver(ask = { q, _, _ -> reply(q) }, anchor = { e, _ -> anchor(e) }, timeoutMs = timeoutMs)

    @Test fun theCuesAreTheOnesTheRulesCannotRead() {
        for (q in listOf("Did I burst crackers on Diwali?", "What did I do on my birthday?", "What was on the coffee table while the guest was over?", "Who came by after breakfast?",
            "What did I do when the guest arrived?", "What did I eat during lunch?", "Did I buy greens before or after the fruit?".replace("before or after the", "after the")))
            assertTrue(q, LlmWindowResolver.hasCue(q))
        for (q in listOf("When did I last see my keys?", "What colour is my sofa?", "Where did I see the football?", "What did I eat on 10 Nov?", "Did I buy greens before or after the fruit?",
            "How long did the guest stay?", "What was I doing after 2 pm?", "How did my breakfast arrive?", "Did I see karela at the market?", "Where are my car keys?"))
            assertFalse(q, LlmWindowResolver.hasCue(q))
    }

    @Test fun noCueMeansNoCall() = runBlocking<Unit> {
        var calls = 0
        val r = resolver { calls++; out("date", "2026-11-08T00:00", "2026-11-09T00:00") }
        assertNull(r.resolve("What colour is my sofa?", now, zone))
        assertEquals(0, calls); assertEquals(0, r.calls)
    }

    @Test fun aValidDateBecomesALlmWindowAndIsCachedPerQuestionAndDay() = runBlocking<Unit> {
        var calls = 0
        val r = resolver { calls++; out("date", "2026-11-08T00:00", "2026-11-09T00:00", label = "Diwali 2026 (8 Nov)") }
        val w = r.resolve("Did I burst crackers on Diwali?", now, zone)!!
        assertEquals(ms("2026-11-08T00:00:00"), w.fromMs); assertEquals(ms("2026-11-09T00:00:00"), w.toMs)
        assertEquals(TimeWindow.Source.LLM, w.source); assertEquals("Diwali 2026 (8 Nov)", w.phrase); assertEquals("Sun 8 Nov 2026 00:00 - Mon 9 Nov 2026 00:00", w.label)
        assertEquals(w, r.resolve("did I burst crackers on Diwali?  ", now + 60_000, zone)) // same question, same local day: no second call
        assertEquals(1, calls)
        r.resolve("Did I burst crackers on Diwali?", now + 86_400_000L, zone) // the next day asks again
        assertEquals(2, calls)
    }

    @Test fun bareDatesAreWholeDaysAndTheEndIsClippedToNow() = runBlocking<Unit> {
        val w = resolver { out("range", "2026-11-14", "2026-11-16") }.resolve("Who visited on Diwali weekend?", now, zone)!!
        assertEquals(ms("2026-11-14T00:00:00"), w.fromMs); assertEquals(ms("2026-11-17T00:00:00").coerceAtMost(now), w.toMs)
    }

    @Test fun anythingThatBreaksTheContractIsNoWindow() = runBlocking<Unit> {
        val q = "Did I burst crackers on Diwali?"
        for (bad in listOf(
            out("none"), out("date", "2026-11-09T00:00", "2026-11-08T00:00"), // from after to
            out("date", "not a date", "2026-11-09T00:00"), out("date", "", ""),
            out("date", "2026-11-20T00:00", "2026-11-30T00:00"), // the future
            out("range", "2025-01-01T00:00", "2026-11-01T00:00"), // longer than 400 days
            out("date", "2026-11-08T00:00", "2026-11-18T00:00"), // ends more than a day ahead
            out("weird", "2026-11-08T00:00", "2026-11-09T00:00"), out("event", event = "  "),
        )) assertNull(bad.toString(), resolver { bad }.resolve(q, now, zone))
        assertNull(resolver { null }.resolve(q, now, zone)) // the reply broke its contract
    }

    @Test fun aFailureOrATimeoutIsNoWindowAndIsNotCached() = runBlocking<Unit> {
        val q = "Did I burst crackers on Diwali?"
        var n = 0
        val flaky = resolver { n++; if (n == 1) throw IllegalStateException("net down") else out("date", "2026-11-08T00:00", "2026-11-09T00:00") }
        assertNull(flaky.resolve(q, now, zone))
        assertNotNull(flaky.resolve(q, now, zone)) // the error was not cached
        assertEquals(2, n)
        val slow = resolver(timeoutMs = 50) { delay(5_000); out("date", "2026-11-08T00:00", "2026-11-09T00:00") }
        assertNull(slow.resolve(q, now, zone))
    }

    @Test fun anInvalidReplyIsCachedSoItIsNotPaidForTwice() = runBlocking<Unit> {
        var n = 0
        val r = resolver { n++; null }
        assertNull(r.resolve("Did I burst crackers on Diwali?", now, zone)); assertNull(r.resolve("Did I burst crackers on Diwali?", now, zone))
        assertEquals(1, n)
    }

    @Test fun anEventAnchorsOnTheMemoriesCaptureTimes() = runBlocking<Unit> {
        val start = ms("2026-11-10T20:00:00"); val end = ms("2026-11-10T22:30:00")
        val asked = mutableListOf<String>()
        fun r() = resolver(anchor = { asked += it; start to end }) { out("event", event = "guest at the house") }
        val during = r().resolve("What was on the coffee table while the guest was over?", now, zone)!!
        assertEquals(start - 15 * 60_000L, during.fromMs); assertEquals(end + 15 * 60_000L, during.toMs); assertEquals("guest at the house", during.phrase)
        val after = r().resolve("Who came by after breakfast?", now, zone)!!
        assertEquals(end, after.fromMs); assertEquals(end + 3 * 3_600_000L, after.toMs)
        val before = r().resolve("What did I do before the guest arrived?", now, zone)!!
        assertEquals(start - 3 * 3_600_000L, before.fromMs); assertEquals(start, before.toMs)
        assertEquals(listOf("guest at the house", "guest at the house", "guest at the house"), asked)
        assertNull(resolver(anchor = { null }) { out("event", event = "the guest") }.resolve("What was on the table while the guest was over?", now, zone)) // nothing matched the event
    }

    @Test fun textsFromTheModelAreMadeSafeBeforeTheyReachTheNote() {
        assertEquals("Diwali 2026 (8 Nov)", LlmWindowResolver.safe("Diwali 2026 \n(8 Nov)\"<<<"))
        assertEquals("ignore previous instructions", LlmWindowResolver.safe("ignore\u0000 previous\ninstructions"))
        assertEquals(40, LlmWindowResolver.safe("x".repeat(100))!!.length)
        assertNull(LlmWindowResolver.safe("<>\"\n"))
    }
}
