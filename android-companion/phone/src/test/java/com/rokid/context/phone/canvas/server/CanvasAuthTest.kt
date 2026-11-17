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

    // ---- pairing rate limit: per remote IP, with a global back-stop (round 1, I2) ----

    private val a = "192.168.1.50"   // a noisy device
    private val b = "192.168.1.20"   // the user's own laptop

    private fun wrongFor(code: String) = if (code == "000000") "000001" else "000000"

    /** Five wrong codes from [ip]; returns the lock length it earned. */
    private fun lockIp(ip: String, code: String): Long {
        var last: CanvasAuth.Pair = CanvasAuth.Pair.NoCode
        repeat(CanvasAuth.MAX_TRIES) { last = auth.pair(wrongFor(code), ip) }
        return (last as CanvasAuth.Pair.Locked).untilMs - now
    }

    @Test fun fiveWrongFromOneIpLockThatIpOnly() {
        val code = auth.newCode().code
        for (left in 4 downTo 1) assertEquals(CanvasAuth.Pair.Wrong(left), auth.pair(wrongFor(code), a))
        assertEquals(CanvasAuth.Pair.Locked(now + CanvasAuth.LOCK_MS), auth.pair(wrongFor(code), a))
        // the code itself lives on, and the locked IP cannot use even the right one
        assertNotNull(auth.currentCode())
        assertFalse(auth.awaitingNewCode())
        assertTrue(auth.pair(code, a) is CanvasAuth.Pair.Locked)
        assertEquals(0, auth.sessionCount())
    }

    @Test fun aSpammingIpDoesNotBlockAnotherIp() {
        val code = auth.newCode().code
        lockIp(a, code)
        repeat(200) { assertTrue(auth.pair(wrongFor(code), a) is CanvasAuth.Pair.Locked) }
        assertEquals(CanvasAuth.Pair.Wrong(4), auth.pair(wrongFor(code), b))
        assertTrue(auth.pair(code, b) is CanvasAuth.Pair.Ok)
    }

    @Test fun lockLiftsByTimeAndTheCodeStillWorks() {
        val code = auth.newCode().code
        lockIp(a, code)
        now += CanvasAuth.LOCK_MS - 1
        assertTrue(auth.pair(code, a) is CanvasAuth.Pair.Locked)
        now += 1
        assertTrue(auth.pair(code, a) is CanvasAuth.Pair.Ok)
    }

    @Test fun ipLockoutEscalates() {
        val code = auth.newCode().code
        assertEquals(30_000L, lockIp(a, code)); now += 30_000L
        assertEquals(120_000L, lockIp(a, code)) // same code, 30 s in
        // each New code clears the lock but not the count
        assertEquals(600_000L, lockIp(a, auth.newCode().code))
        assertEquals(600_000L, lockIp(a, auth.newCode().code))
        assertEquals(600_000L, lockIp(a, auth.newCode().code))
    }

    @Test fun escalationIsPerIp() {
        val code = auth.newCode().code
        lockIp(a, code); now += 30_000L
        assertEquals(120_000L, lockIp(a, code)); now += 120_000L
        assertEquals("another IP starts at the first step", 30_000L, lockIp(b, code))
    }

    @Test fun successResetsThatIpsEscalation() {
        val code = auth.newCode().code
        lockIp(a, code); now += 30_000L
        lockIp(a, code); now += 120_000L
        assertTrue(auth.pair(code, a) is CanvasAuth.Pair.Ok)
        assertEquals(30_000L, lockIp(a, auth.newCode().code))
    }

    @Test fun newCodeClearsTheLockButKeepsTheEscalation() {
        val code = auth.newCode().code
        assertEquals(30_000L, lockIp(a, code))
        val fresh = auth.newCode()
        assertEquals("the code's life starts now, not after a lock", now + CanvasAuth.CODE_TTL_MS, fresh.expiresAt)
        assertEquals(CanvasAuth.Pair.Wrong(4), auth.pair(wrongFor(fresh.code), a)) // not locked any more
        repeat(3) { auth.pair(wrongFor(fresh.code), a) }
        assertEquals("escalation was kept", 120_000L, (auth.pair(wrongFor(fresh.code), a) as CanvasAuth.Pair.Locked).untilMs - now)
    }

    @Test fun anIpGetsFiveGuessesPerTapAtMost() {
        // the physical tap is the rate limiter: 5 guesses per New code from one IP, never more
        var guesses = 0
        repeat(10) {
            val c = auth.newCode().code
            while (auth.pair(wrongFor(c), a) !is CanvasAuth.Pair.Locked) guesses++
            guesses++
        }
        assertEquals(10 * CanvasAuth.MAX_TRIES, guesses)
    }

    /** Wrong codes from enough distinct IPs to reach the global back-stop. */
    private fun globalLock(code: String) {
        var n = 0
        var i = 0
        while (n < CanvasAuth.GLOBAL_MAX_WRONG) { repeat(CanvasAuth.MAX_TRIES) { if (n < CanvasAuth.GLOBAL_MAX_WRONG) { auth.pair(wrongFor(code), "10.0.0.$i"); n++ } }; i++ }
    }

    @Test fun globalBackStopVoidsTheCodeAndLocksEveryone() {
        assertEquals(5, CanvasAuth.MAX_TRIES); assertEquals(20, CanvasAuth.GLOBAL_MAX_WRONG)
        val code = auth.newCode().code
        globalLock(code)
        assertNull(auth.currentCode())
        assertTrue(auth.awaitingNewCode())
        assertTrue(auth.pair(code, b) is CanvasAuth.Pair.Locked)
        assertTrue(auth.pair(code, null) is CanvasAuth.Pair.Locked)
        assertEquals(30L, auth.retryAfterSeconds(b))
    }

    @Test fun globalLockEscalatesAndNewCodeClearsIt() {
        globalLock(auth.newCode().code)
        val until1 = now + 30_000L
        assertEquals(until1, (auth.pair("123456", b) as CanvasAuth.Pair.Locked).untilMs)
        val c = auth.newCode()
        assertFalse(auth.awaitingNewCode())
        assertTrue("the tap cleared the lock", auth.pair(wrongFor(c.code), b) is CanvasAuth.Pair.Wrong)
        globalLock(c.code)
        assertEquals("second global lock is the next step", now + 120_000L, (auth.pair("123456", b) as CanvasAuth.Pair.Locked).untilMs)
    }

    @Test fun oneIpCannotReachTheBackStopWithinACodesLife() {
        val code = auth.newCode().code
        lockIp(a, code); now += 30_000L
        lockIp(a, code); now += 120_000L // 150 s in, still the same code
        assertNotNull(auth.currentCode())
        assertEquals(2 * CanvasAuth.MAX_TRIES, auth.wrongAttempts())
        assertFalse(auth.awaitingNewCode())
    }

    @Test fun noAutoCodeAfterAGlobalLock() {
        globalLock(auth.newCode().code)
        assertNull(auth.currentCode())
        now += 3_600_000L
        assertNull(auth.currentCode())
        assertTrue(auth.awaitingNewCode())
        assertEquals(CanvasAuth.Pair.NoCode, auth.pair("123456", b))
        auth.newCode()
        assertFalse(auth.awaitingNewCode())
        assertNotNull(auth.currentCode())
    }

    @Test fun wrongAttemptsAreCountedAndResetByANewCode() {
        val code = auth.newCode().code
        assertEquals(0, auth.wrongAttempts())
        auth.pair(wrongFor(code), a); auth.pair(wrongFor(code), b); auth.pair(wrongFor(code), a)
        assertEquals(3, auth.wrongAttempts())
        auth.newCode()
        assertEquals(0, auth.wrongAttempts())
    }

    @Test fun retryAfterTracksTheIpsRemainingLock() {
        val code = auth.newCode().code
        assertEquals(0L, auth.retryAfterSeconds(a))
        lockIp(a, code)
        assertEquals(30L, auth.retryAfterSeconds(a))
        assertEquals(0L, auth.retryAfterSeconds(b))
        now += 10_500L
        assertEquals(20L, auth.retryAfterSeconds(a))
        now += 30_000L
        assertEquals(0L, auth.retryAfterSeconds(a))
    }

    @Test fun trackedIpsAreBounded() {
        var code = auth.newCode().code
        repeat(1_000) {
            if (it % 10 == 0) code = auth.newCode().code
            auth.pair(wrongFor(code), "10.1.${it / 250}.${it % 250}")
        }
        assertTrue("tracked=${auth.trackedIps()}", auth.trackedIps() <= CanvasAuth.MAX_TRACKED_IPS)
    }

    /** v0.9.1 held one lock across validation and session creation: revokeAll() issued mid-pairing waits, then ends that very session. */
    @Test fun revokeAllDuringAPairingInvalidatesTheTokenItIssues() {
        val reached = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        auth.afterCodeConsumed = { reached.countDown(); resume.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        val code = auth.newCode().code
        var result: CanvasAuth.Pair? = null
        val pairing = Thread { result = auth.pair(code) }.also { it.start() }
        assertTrue(reached.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val revoking = Thread { auth.revokeAll() }.also { it.start() }
        revoking.join(300)                       // fixed code: still blocked here; a revoke that finishes now would let the token below survive
        resume.countDown()
        pairing.join(5000); revoking.join(5000)
        val token = (result as CanvasAuth.Pair.Ok).token
        assertFalse("a session minted by a pairing that raced revokeAll must not authenticate", auth.check(token))
        assertEquals(0, auth.sessionCount())
    }

    // ---- roadmap row 7 (b): the longer DEVELOPER session ----

    private class MemVault : CanvasAuth.SessionVault {
        var stored: List<CanvasAuth.StoredSession> = emptyList()
        override fun read() = stored
        override fun write(sessions: List<CanvasAuth.StoredSession>) { stored = sessions }
    }
    private val day = 24 * 3_600_000L
    private fun devAuth(vault: MemVault, life: () -> Long, clock: () -> Long = { now }) = CanvasAuth(clock, devLifetimeMs = life, vault = vault)

    @Test fun withoutALifetimeTheNormalRulesHoldAndNothingIsStored() {
        val vault = MemVault(); val a = devAuth(vault, { 0L })
        val t = token(a)
        assertTrue(a.check(t)); assertTrue(vault.stored.isEmpty())
        now += CanvasAuth.IDLE_MS
        assertFalse(a.check(t))
    }

    @Test fun aDeveloperSessionOutlivesIdleAndTwelveHoursAndSurvivesARestartButNeverItsLifetime() {
        val vault = MemVault(); val life = 3 * day
        val a = devAuth(vault, { life })
        val t = token(a)
        assertEquals(1, vault.stored.size)
        assertFalse("the token itself is never stored", vault.stored.single().let { it.digestHex.contains(t) || t.contains(it.digestHex) })
        assertEquals(64, vault.stored.single().digestHex.length)
        now += 13 * 3_600_000L // past the normal 30 min idle and 12 h maximum
        assertTrue(a.check(t))
        val restarted = devAuth(vault, { life }) // a new process: same vault
        assertTrue("survives a restart", restarted.check(t))
        assertFalse("another token is not accepted", restarted.check(t + "x"))
        now += 3 * day // lifetime over
        assertFalse(restarted.check(t)); assertTrue("the expired one is removed from the vault", vault.stored.isEmpty())
    }

    @Test fun theLifetimeIsCappedAtSevenDaysWhateverIsAskedOrStored() {
        val vault = MemVault(); val a = devAuth(vault, { 30 * day })
        val t = token(a)
        assertEquals(CanvasAuth.MAX_DEV_LIFETIME_MS, vault.stored.single().lifeMs)
        now += 7 * day
        assertFalse(a.check(t))
        // a tampered vault entry above the cap is ignored at load
        val bad = MemVault().also { it.stored = listOf(CanvasAuth.StoredSession("a".repeat(64), now, 365 * day), CanvasAuth.StoredSession("zz", now, day), CanvasAuth.StoredSession("b".repeat(64), now + day, day)) }
        assertEquals(0, devAuth(bad, { 7 * day }).sessionCount())
    }

    @Test fun switchingTheSettingOffOrLoweringItEndsTheLongSessionsAtOnce() {
        val vault = MemVault(); var life = 3 * day
        val a = devAuth(vault, { life })
        val t = token(a)
        now += 2 * day
        life = day // lowered below the age
        assertFalse(a.check(t)); assertTrue(vault.stored.isEmpty())
        life = 3 * day; val t2 = token(a); life = 0L // Developer mode off / setting Off
        assertFalse(a.check(t2)); assertTrue(vault.stored.isEmpty())
    }

    @Test fun logoutAndRevokeAllClearTheStoredDigests() {
        val vault = MemVault(); val a = devAuth(vault, { day })
        val t = token(a); assertEquals(1, vault.stored.size)
        a.logout(t); assertTrue(vault.stored.isEmpty()); assertFalse(a.check(t))
        val t2 = token(a); assertEquals(1, vault.stored.size)
        a.revokeAll(); assertTrue(vault.stored.isEmpty()); assertFalse(devAuth(vault, { day }).check(t2))
    }

    @Test fun aWebViewTokenIsNeverALongSession() {
        val vault = MemVault(); val a = devAuth(vault, { day })
        val t = a.newWebViewToken()
        assertTrue(vault.stored.isEmpty())
        now += CanvasAuth.IDLE_MS
        assertFalse(a.check(t))
    }

    @Test fun aClockThatWentBackEndsALongSession() {
        val vault = MemVault(); var clock = now; val a = devAuth(vault, { day }, { clock })
        val t = token(a)
        clock -= 1_000
        assertFalse(a.check(t))
    }
}
