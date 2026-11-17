package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.contract.CondenseOutput
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval-quality spec section 10 (T10): a fake model call stands in for the runner. The eval questions (qna-set-v1) with the replies a good model gives: the date words and the question
 * words are gone from the search query; a broken reply, a blank query, a timeout or an exception = no condensing (the raw question is searched); one call per (question, day); another language
 * is translated and its own-language search stays as a second probe.
 */
class QueryCondenserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = ms("2026-11-16T12:04:00")
    private val dateAndQuestionWords = Regex("\\b(did|do|does|where|what|when|which|how|is|are|my|i|you|today|yesterday|tonight|last|nov|november|on|at 12)\\b", RegexOption.IGNORE_CASE)

    private fun condenser(timeoutMs: Long = 3_000, reply: suspend (String) -> CondenseOutput?) = QueryCondenser(ask = { q, _, _ -> reply(q) }, timeoutMs = timeoutMs)

    /** question to the model's reply: q12/q13/q51 are the flat-score questions of spec section 13, q17/q42 "last", q06 the karela question. */
    private val table = listOf(
        Triple("Where did I see the football?", CondenseOutput("football", "en"), "football"),
        Triple("Where did I see the soccer ball on 12 Nov?", CondenseOutput("soccer ball", "en"), "soccer ball"),
        Triple("Where did I see the football today?", CondenseOutput("football", "en"), "football"),
        Triple("When did I last see my keys?", CondenseOutput("keys", "en"), "keys"),
        Triple("When did I last use my laptop?", CondenseOutput("laptop", "en"), "laptop"),
        Triple("What does my sofa cover look like?", CondenseOutput("sofa cover", "en"), "sofa cover"),
        Triple("What did I eat yesterday evening?", CondenseOutput("eat food meal", "en"), "eat food meal"),
    )

    @Test fun dateWordsAndQuestionWordsAreGoneForEnglishQuestions() = runBlocking<Unit> {
        for ((question, reply, expected) in table) {
            val c = condenser { reply }.condense(question, now, zone)!!
            assertEquals(question, expected, c.query)
            assertFalse(question, dateAndQuestionWords.containsMatchIn(c.query))
            assertNull("an English question has no second search: $question", c.probe)
            assertEquals("en", c.lang)
        }
    }

    @Test fun theKarelaQuestionIsTranslatedAndKeepsItsOwnLanguageProbe() = runBlocking<Unit> {
        val q = "Did I see karela at the market?" // eval q06, needs: multilingual
        val c = condenser { CondenseOutput("bitter gourd market stall", "hi") }.condense(q, now, zone)!!
        assertEquals("bitter gourd market stall", c.query); assertEquals(q, c.probe); assertEquals("hi", c.lang)
        // Hindi in Devanagari, a regional code with a region tag, and Hinglish all count
        assertEquals("मैंने बाज़ार में करेला देखा?", condenser { CondenseOutput("bitter gourd market", "hi-IN") }.condense("मैंने बाज़ार में करेला देखा?", now, zone)!!.probe)
        assertEquals("ta", condenser { CondenseOutput("bitter gourd", "TA") }.condense("சந்தையில் பாகற்காய் பார்த்தேனா?", now, zone)!!.lang)
        // a blank language is judged by the script: Devanagari is foreign, plain Latin is not
        assertEquals("मैंने करेला देखा", condenser { CondenseOutput("bitter gourd", "") }.condense("मैंने करेला देखा", now, zone)!!.probe)
        assertNull(condenser { CondenseOutput("football", "") }.condense("Where is the football?", now, zone)!!.probe)
    }

    @Test fun translateOffKeepsTheModelsQueryAndNoSecondSearch() = runBlocking<Unit> {
        val c = condenser { CondenseOutput("bitter gourd market", "hi") }.condense("Did I see karela at the market?", now, zone, translate = false)!!
        assertEquals("bitter gourd market", c.query); assertNull(c.probe)
    }

    @Test fun aBrokenReplyIsNoCondensingNeverAnError() = runBlocking<Unit> {
        assertNull(condenser { null }.condense("Where did I see the football?", now, zone))
        assertNull(condenser { CondenseOutput("   ", "en") }.condense("Where did I see the football?", now, zone))
        assertNull(condenser { CondenseOutput("\"\" {} <> ;;", "en") }.condense("Where did I see the football?", now, zone))
        assertNull(condenser { throw IllegalStateException("transport") }.condense("Where did I see the football?", now, zone))
    }

    @Test fun aSlowModelIsCutAtTheTimeoutAndNotCached() = runBlocking<Unit> {
        var calls = 0
        val c = condenser(timeoutMs = 40) { calls++; if (calls == 1) { delay(2_000); CondenseOutput("football", "en") } else CondenseOutput("football", "en") }
        assertNull(c.condense("Where did I see the football?", now, zone))
        assertEquals("football", c.condense("Where did I see the football?", now, zone)!!.query) // the next ask tries again
        assertEquals(2, calls)
    }

    @Test fun oneCallPerQuestionAndDayAndAContractFailureIsCachedToo() = runBlocking<Unit> {
        var calls = 0
        val c = condenser { calls++; CondenseOutput("football", "en") }
        c.condense("Where did I see the football?", now, zone)
        c.condense("  where did I see the FOOTBALL?  ", now + 60_000, zone) // same question, same local day
        assertEquals(1, calls); assertEquals(1, c.calls)
        c.condense("Where did I see the football?", now + 86_400_000L, zone) // the next day asks again
        assertEquals(2, calls)
        var broken = 0
        val b = condenser { broken++; null }
        assertNull(b.condense("Where is my sofa?", now, zone)); assertNull(b.condense("Where is my sofa?", now, zone))
        assertEquals(1, broken)
    }

    @Test fun theQueryIsPlainWordsOfBoundedLength() {
        assertEquals("bitter gourd x", QueryCondenser.clean("\"bitter gourd\"; {x} <>"))
        assertEquals("करेला bitter gourd", QueryCondenser.clean("करेला, bitter gourd"))
        assertEquals(12, QueryCondenser.clean((1..40).joinToString(" ") { "w$it" })!!.split(" ").size)
        assertTrue(QueryCondenser.clean("a ".repeat(200))!!.length <= 120)
        assertNull(QueryCondenser.clean(" ;; "))
    }
}
