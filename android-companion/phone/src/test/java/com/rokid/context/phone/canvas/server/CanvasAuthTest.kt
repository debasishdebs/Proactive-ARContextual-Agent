package com.rokid.context.phone.canvas.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasAuthTest {
    private var now = 1_000_000L
    private val auth = CanvasAuth(clock = { now })

    private fun token(a: CanvasAuth = auth): String {
        val code = a.newCode().code
        return (a.pair(code) as CanvasAuth.Pair.Ok).token
    }

    @Test fun codeIsSixDigits() {
        repeat(300) {
            val c = auth.newCode()
            assertTrue(c.code, Regex("[0-9]{6}").matches(c.code))
            assertEquals(now + CanvasAuth.CODE_TTL_MS, c.expiresAt)
        }
        assertEquals(auth.currentCode()?.code, auth.currentCode()?.code)
    }

    @Test fun codeSingleUse() {
        val code = auth.newCode().code
        assertTrue(auth.pair(code) is CanvasAuth.Pair.Ok)
        assertNull(auth.currentCode())
        assertEquals(CanvasAuth.Pair.NoCode, auth.pair(code))
    }

    @Test fun codeExpires() {
        val code = auth.newCode().code
        now += CanvasAuth.CODE_TTL_MS - 1
        assertNotNull(auth.currentCode())
        now += 1
        assertNull(auth.currentCode())
        assertEquals(CanvasAuth.Pair.NoCode, auth.pair(code))
    }

    @Test fun newCodeReplacesThePreviousOne() {
        val first = auth.newCode().code
        var second = auth.newCode().code
        while (second == first) second = auth.newCode().code
        assertTrue(auth.pair(first) is CanvasAuth.Pair.Wrong)
        assertTrue(auth.pair(second) is CanvasAuth.Pair.Ok)
    }

    @Test fun noCodeIssuedIsNoCode() {
        assertEquals(CanvasAuth.Pair.NoCode, auth.pair("123456"))
    }

    @Test fun fiveWrongTriesVoidAndLock() {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        for (left in 4 downTo 1) assertEquals(CanvasAuth.Pair.Wrong(left), auth.pair(wrong))
        assertEquals(CanvasAuth.Pair.Locked(now + CanvasAuth.LOCK_MS), auth.pair(wrong))
        assertNull(auth.currentCode())
        // The right code no longer works: the code is void and pairing is locked.
        assertTrue(auth.pair(code) is CanvasAuth.Pair.Locked)
        assertEquals(0, auth.sessionCount())
    }

    @Test fun lockLifts() {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(5) { auth.pair(wrong) }
        now += CanvasAuth.LOCK_MS - 1
        val fresh = auth.newCode().code
        assertTrue(auth.pair(fresh) is CanvasAuth.Pair.Locked)
        now += 1
        val again = auth.newCode().code
        assertTrue(auth.pair(again) is CanvasAuth.Pair.Ok)
    }

    @Test fun triesResetWithANewCode() {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(4) { auth.pair(wrong) }
        val fresh = auth.newCode().code
        val wrong2 = if (fresh == "000000") "000001" else "000000"
        assertEquals(CanvasAuth.Pair.Wrong(4), auth.pair(wrong2))
    }

    @Test fun malformedCodeCountsAsWrong() {
        auth.newCode()
        assertEquals(CanvasAuth.Pair.Wrong(4), auth.pair(""))
        assertEquals(CanvasAuth.Pair.Wrong(3), auth.pair("12345678901234567890"))
    }

    @Test fun tokenIdleExpiry() {
        val t = token()
        now += CanvasAuth.IDLE_MS - 1
        assertTrue(auth.check(t))
        now += CanvasAuth.IDLE_MS - 1 // the check touched it
        assertTrue(auth.check(t))
        now += CanvasAuth.IDLE_MS
        assertFalse(auth.check(t))
        assertEquals(0, auth.sessionCount())
        assertFalse(auth.check(t))
    }

    @Test fun tokenMaxAge() {
        val t = token()
        val start = now
        while (now - start < CanvasAuth.MAX_AGE_MS - 20 * 60_000L) {
            now += 20 * 60_000L
            assertTrue(auth.check(t))
        }
        now = start + CanvasAuth.MAX_AGE_MS
        assertFalse(auth.check(t))
        assertEquals(0, auth.sessionCount())
    }

    @Test fun fourthSessionEvictsOldest() {
        val a = token(); now += 1
        val b = token(); now += 1
        val c = token(); now += 1
        assertEquals(3, auth.sessionCount())
        val d = token()
        assertEquals(3, auth.sessionCount())
        assertFalse(auth.check(a))
        assertTrue(auth.check(b)); assertTrue(auth.check(c)); assertTrue(auth.check(d))
    }

    @Test fun revokeAll() {
        val a = token(); val b = auth.newWebViewToken()
        auth.revokeAll()
        assertEquals(0, auth.sessionCount())
        assertFalse(auth.check(a)); assertFalse(auth.check(b))
    }

    @Test fun logoutEndsOnlyThatSession() {
        val a = token(); val b = token()
        auth.logout(a)
        assertFalse(auth.check(a)); assertTrue(auth.check(b))
    }

    @Test fun webViewTokenValid() {
        val t = auth.newWebViewToken()
        assertTrue(auth.check(t))
        assertEquals(43, t.length) // 32 bytes, base64url without padding
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(t))
        assertNotEquals(t, auth.newWebViewToken())
    }

    @Test fun missingOrWrongTokenIsRejected() {
        token()
        assertFalse(auth.check(null))
        assertFalse(auth.check(""))
        assertFalse(auth.check("nope"))
    }

    @Test fun lastActivityTracksSuccessfulChecksOnly() {
        val t = token()
        now += 1_000
        assertFalse(auth.check("bad"))
        val before = auth.lastActivityMs
        assertTrue(before < now)
        assertTrue(auth.check(t))
        assertEquals(now, auth.lastActivityMs)
    }

    private fun lockOut(): Long {
        val code = auth.newCode().code
        val wrong = if (code == "000000") "000001" else "000000"
        var last: CanvasAuth.Pair = CanvasAuth.Pair.NoCode
        repeat(CanvasAuth.MAX_TRIES) { last = auth.pair(wrong) }
        return (last as CanvasAuth.Pair.Locked).untilMs - now
    }

    @Test fun noAutoCodeAfterLockout() {
        lockOut()
        assertNull(auth.currentCode())
        assertTrue(auth.awaitingNewCode())
        // time passing never mints one; only an explicit newCode() does
        now += 3_600_000L
        assertNull(auth.currentCode())
        assertTrue(auth.awaitingNewCode())
        assertEquals(CanvasAuth.Pair.NoCode, auth.pair("123456"))
        auth.newCode()
        assertFalse(auth.awaitingNewCode())
        assertNotNull(auth.currentCode())
    }

    @Test fun lockoutEscalates() {
        assertEquals(30_000L, lockOut())
        now += 30_000L
        assertEquals(120_000L, lockOut())
        now += 120_000L
        assertEquals(600_000L, lockOut())
        now += 600_000L
        assertEquals(600_000L, lockOut())
        now += 600_000L
        assertEquals(600_000L, lockOut())
    }

    @Test fun successResetsEscalation() {
        lockOut(); now += 30_000L
        lockOut(); now += 120_000L
        val ok = auth.newCode().code
        assertTrue(auth.pair(ok) is CanvasAuth.Pair.Ok)
        assertEquals(30_000L, lockOut())
    }

    @Test fun codeMintedDuringALockOnlyStartsItsLifeAfterTheLock() {
        lockOut() // 30 s
        val c = auth.newCode()
        assertEquals(now + 30_000L + CanvasAuth.CODE_TTL_MS, c.expiresAt)
        assertTrue(auth.pair(c.code) is CanvasAuth.Pair.Locked)
        now += 30_000L
        assertTrue(auth.pair(c.code) is CanvasAuth.Pair.Ok)
    }

    @Test fun retryAfterTracksTheRemainingLock() {
        assertEquals(0L, auth.retryAfterSeconds())
        lockOut()
        assertEquals(30L, auth.retryAfterSeconds())
        now += 10_500L
        assertEquals(20L, auth.retryAfterSeconds())
        now += 30_000L
        assertEquals(0L, auth.retryAfterSeconds())
    }

    @Test fun lockedAttemptsDoNotConsumeOrExtendTheLock() {
        val len = lockOut()
        val until = now + len
        repeat(50) { assertEquals(CanvasAuth.Pair.Locked(until), auth.pair("000000")) }
        now = until
        val ok = auth.newCode().code
        assertTrue(auth.pair(ok) is CanvasAuth.Pair.Ok)
    }
}
