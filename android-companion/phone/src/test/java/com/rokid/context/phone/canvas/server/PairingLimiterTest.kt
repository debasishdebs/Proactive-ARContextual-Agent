package com.rokid.context.phone.canvas.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP2: the pairing limiter extracted from CanvasAuth. CanvasAuthTest keeps pinning the browser behaviour through CanvasAuth; this pins the extracted class itself. */
class PairingLimiterTest {
    private var now = 1_000_000L
    private fun limiter(ttl: Long = PairingLimiter.CODE_TTL_MS) = PairingLimiter(clock = { now }, codeTtlMs = ttl)

    private fun wrongOf(code: String) = if (code == "000000") "000001" else "000000"

    @Test fun codeCarriesTheConfiguredTtl() {
        val l = limiter(ttl = 120_000L)
        assertEquals(now + 120_000L, l.newCode().expiresAt)
        now += 119_999L
        assertTrue(l.currentCode() != null)
        now += 1
        assertNull(l.currentCode())
        assertEquals(PairingLimiter.Result.NoCode, l.attempt("123456", null))
    }

    @Test fun correctCodeIsSingleUse() {
        val l = limiter()
        val c = l.newCode().code
        assertEquals(PairingLimiter.Result.Ok, l.attempt(c, "10.0.0.2"))
        assertEquals(PairingLimiter.Result.NoCode, l.attempt(c, "10.0.0.2"))
    }

    @Test fun fiveWrongFromOneIpLockThatIpOnly() {
        val l = limiter()
        val c = l.newCode().code
        repeat(4) { assertTrue(l.attempt(wrongOf(c), "10.0.0.2") is PairingLimiter.Result.Wrong) }
        assertTrue(l.attempt(wrongOf(c), "10.0.0.2") is PairingLimiter.Result.Locked)
        assertTrue("locked ip refused even with the right code", l.attempt(c, "10.0.0.2") is PairingLimiter.Result.Locked)
        assertEquals(PairingLimiter.Result.Ok, l.attempt(c, "10.0.0.3"))
    }

    @Test fun twentyWrongAcrossIpsVoidTheCodeUntilANewOne() {
        val l = limiter()
        val c = l.newCode().code
        for (i in 0 until PairingLimiter.GLOBAL_MAX_WRONG) l.attempt(wrongOf(c), "10.0.1.$i")
        assertTrue(l.awaitingNewCode())
        assertTrue(l.attempt(c, "10.0.9.9") is PairingLimiter.Result.Locked)
        val n = l.newCode().code
        assertEquals(PairingLimiter.Result.Ok, l.attempt(n, "10.0.9.9"))
    }

    @Test fun twoLimitersShareNothing() {
        val a = limiter(); val b = limiter()
        val ca = a.newCode().code; b.newCode()
        repeat(PairingLimiter.GLOBAL_MAX_WRONG) { a.attempt(wrongOf(ca), "10.0.2.$it") }
        assertTrue(a.awaitingNewCode())
        assertTrue("the other limiter is untouched", !b.awaitingNewCode())
        assertEquals(0, b.wrongAttempts())
    }

    @Test fun trackedIpsAreBounded() {
        val l = limiter()
        val c = l.newCode().code
        repeat(PairingLimiter.MAX_TRACKED_IPS * 3) { l.attempt(wrongOf(c), "10.9.$it.1") }
        assertTrue(l.trackedIps() <= PairingLimiter.MAX_TRACKED_IPS)
    }

    @Test fun canvasAuthKeepsItsConstantsAndDelegates() {
        assertEquals(PairingLimiter.CODE_TTL_MS, CanvasAuth.CODE_TTL_MS)
        assertEquals(PairingLimiter.MAX_TRIES, CanvasAuth.MAX_TRIES)
        assertEquals(PairingLimiter.GLOBAL_MAX_WRONG, CanvasAuth.GLOBAL_MAX_WRONG)
        assertEquals(PairingLimiter.MAX_TRACKED_IPS, CanvasAuth.MAX_TRACKED_IPS)
        assertEquals(PairingLimiter.LOCK_STEPS_MS.toList(), CanvasAuth.LOCK_STEPS_MS.toList())
        // the guess counting lives in the limiter only: CanvasAuth carries no copy of it
        val src = java.io.File("src/main/java/com/rokid/context/phone/canvas/server/CanvasAuth.kt").readText()
        assertTrue("CanvasAuth still holds the guess counters", !src.contains("wrongTotal") && !src.contains("class IpState"))
    }
}
