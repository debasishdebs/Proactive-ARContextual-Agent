package com.rokid.context.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmTokensTest {
    private var now = 1_000L
    private val tokens = ConfirmTokens({ now })

    @Test fun issuesSixteenRandomBytesAsHex() {
        val a = tokens.issue("h", listOf("f1"))
        val b = tokens.issue("h", listOf("f1"))
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == b)
    }

    @Test fun expiresAfterFiveMinutes() {
        val t = tokens.issue("h", listOf("f1"))
        now += 5 * 60 * 1000L
        assertFalse(tokens.consume(t, "h", listOf("f1")))
        now = 1_000L
        val ok = tokens.issue("h", listOf("f1"))
        now += 5 * 60 * 1000L - 1
        assertTrue(tokens.consume(ok, "h", listOf("f1")))
    }

    @Test fun rejectsDifferentFrames() {
        val t = tokens.issue("h", listOf("f1", "f2"))
        assertFalse(tokens.consume(t, "h", listOf("f1")))
        val u = tokens.issue("h", listOf("f1", "f2"))
        assertFalse(tokens.consume(u, "other", listOf("f1", "f2")))
        val v = tokens.issue("h", listOf("f1", "f2"))
        assertTrue(tokens.consume(v, "h", listOf("f2", "f1")))
    }

    @Test fun isSingleUse() {
        val t = tokens.issue("h", listOf("f1"))
        assertTrue(tokens.consume(t, "h", listOf("f1")))
        assertFalse(tokens.consume(t, "h", listOf("f1")))
        assertFalse(tokens.consume("nope", "h", listOf("f1")))
    }
}
