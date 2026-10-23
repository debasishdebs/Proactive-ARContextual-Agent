package com.rokid.context.canvas.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1 (2026-10-24): [ResultWire] verifies the claimed hash over the received string, then parses it strictly. The shared rows are in HelperProtocolFixturesTest.resultWireRows. */
class ResultWireTest {
    private fun sha(t: String) = BundleHash.ofCanonical(t)
    private fun kind(t: String, hash: String = sha(t)) = (ResultWire.check(t, hash) as? ResultWire.Checked.Rejected)?.kind
    private fun ok(t: String) = ResultWire.check(t, sha(t)) as ResultWire.Checked.Ok

    @Test fun theLimitIsMeasuredOnTheUtf8BytesOfTheString() {
        val max = HelperProtocol.MAX_RESULT_BYTES.toInt()
        fun obj(fill: String, n: Int) = "{\"a\":\"" + fill.repeat(n) + "\"}"
        val atLimit = obj("x", max - 8)
        assertEquals(max, atLimit.toByteArray(Charsets.UTF_8).size)
        assertEquals(null, kind(atLimit))
        assertEquals(ResultWire.Kind.TOO_LARGE, kind(obj("x", max - 7)))
        val manyBytes = obj("é", (max - 8) / 2 + 1) // about half the limit in characters, over it in bytes
        assertTrue(manyBytes.length < max)
        assertEquals(ResultWire.Kind.TOO_LARGE, kind(manyBytes))
    }

    @Test fun theHashIsOverTheExactUtf8BytesOfTheReceivedString() {
        val t = "{\"s\":\"café 日本語 😀\"}"
        assertEquals(null, kind(t))
        assertEquals(ResultWire.Kind.HASH, kind(t, sha("{\"s\":\"cafe\u0301 日本語 😀\"}")))
        assertEquals(ResultWire.Kind.HASH, kind(t, sha(t).uppercase()))
        assertEquals(ResultWire.Kind.HASH, kind(t, sha(t).take(63)))
    }

    @Test fun numbersKeepTheirTextAndAreNeverReprinted() {
        val r = ok("""{"a":[0.730,1E-5,-0.0,100.0,1e+16,5e-324,1.7976931348623157e+308,-3]}""").result
        val xs = (r.getValue("a") as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
        assertEquals(listOf("0.730", "1E-5", "-0.0", "100.0", "1e+16", "5e-324", "1.7976931348623157e+308", "-3"), xs)
    }

    @Test fun theParseIsStrictJson() {
        for (bad in listOf("", " ", "{", "{}x", "{} {}", "{\"a\":}", "{\"a\":1,}", "[1,]", "{'a':1}", "{\"a\":+1}", "{\"a\":.5}", "{\"a\":1.}", "{\"a\":01}", "{\"a\":0x1}",
            "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":-Infinity}", "{\"a\":tru}", "{\"a\":\"\\x\"}", "{\"a\":\"\\ud800\"}", "{\"a\":\"\\udc00x\"}", "{\"a\":\"\n\"}", "\uFEFF{}", "{\"a\":1e}", "{\"a\":--1}", "{/*c*/}")) {
            assertEquals(bad, ResultWire.Kind.JSON, kind(bad))
        }
        for (good in listOf("{}", " \t\r\n{ \"a\" : [ 1 , 2.5e3 , \"\\u00e9\\ud83d\\ude00\" , null , true , false ] }\n", "{\"a\":-0}", "{\"a\":0.0}")) assertEquals(good, null, kind(good))
        assertEquals("é😀", ((ok("{\"a\":\"\\u00e9\\ud83d\\ude00\"}").result.getValue("a")) as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun anUnpairedSurrogateInTheReceivedStringIsRefusedNotHashedAsAReplacement() {
        val t = "{\"a\":\"\uD800\"}"
        assertEquals(ResultWire.Kind.JSON, kind(t, sha(t)))
    }

    @Test fun duplicateKeysAreRefusedAtEveryDepthEvenWhenSpelledDifferently() {
        for (d in listOf("""{"a":1,"a":2}""", """{"x":{"a":1,"b":2,"a":3}}""", """{"x":[{"a":1,"a":1}]}""", """{"a":1,"\u0061":2}""")) assertEquals(d, ResultWire.Kind.DUPLICATE_KEY, kind(d))
        assertEquals(null, kind("""{"a":{"a":1},"b":{"a":2}}"""))
    }

    @Test fun onlyAnObjectIsAResult() {
        for (t in listOf("[1]", "0.73", "\"s\"", "null", "true")) assertEquals(t, ResultWire.Kind.NOT_OBJECT, kind(t))
    }

    @Test fun veryDeepNestingIsRefusedNotAStackOverflow() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        assertEquals(ResultWire.Kind.JSON, kind("{\"a\":$deep}"))
    }
}
