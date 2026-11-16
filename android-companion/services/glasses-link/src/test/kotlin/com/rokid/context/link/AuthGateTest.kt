package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthGateTest {
    @Test fun launchesOncePerAttemptUntilItGoesStale() {
        val g = AuthGate(timeoutMs = 30_000)
        assertTrue(g.begin(0))
        assertFalse(g.begin(10_000))
        assertTrue(g.begin(30_000), "stale attempt may be relaunched")
    }

    @Test fun expiryAndStaleClearFreeTheGate() {
        val g = AuthGate(30_000)
        g.begin(0); g.expire()
        assertTrue(g.begin(1))
        g.clearStale(31_001)
        assertTrue(g.begin(31_002))
    }

    @Test fun lateFailureAfterSuccessIsIgnored() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(true))
        assertFalse(g.result(false))
        assertFalse(g.result(true), "duplicate success")
        assertTrue(g.authorized)
    }

    @Test fun failureIsAppliedAndClearsInFlight() {
        val g = AuthGate()
        g.begin(0)
        assertTrue(g.result(false))
        assertTrue(g.begin(1))
    }

    @Test fun rejectedTokenRevokesAuthorizationAndAllowsOneReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected(), "first rejection: go through authorization again")
        assertFalse(g.authorized)
        assertTrue(g.begin(1), "a new attempt may launch at once")
        assertTrue(g.result(true))
        assertTrue(g.authorized)
    }

    @Test fun tokenRejectedAgainRightAfterReauthorizingGivesUp() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        assertTrue(g.tokenRejected())
        g.begin(1); g.result(true)
        assertFalse(g.tokenRejected(), "no relaunch loop")
        assertFalse(g.authorized)
    }

    @Test fun aStartedSessionRearmsReauthorization() {
        val g = AuthGate()
        g.begin(0); g.result(true)
        g.tokenRejected(); g.begin(1); g.result(true)
        g.sessionStarted()
        assertTrue(g.tokenRejected(), "a later expiry is a new rejection")
    }

    /** close() cancels the attempt: a late AuthActivity result must not reopen a session after stop. */
    @Test fun cancelledAttemptIgnoresLateResultsUntilTheNextBegin() {
        val g = AuthGate()
        g.begin(0); g.cancel()
        assertFalse(g.result(true), "late success after close")
        assertFalse(g.authorized)
        assertTrue(g.begin(1))
        assertTrue(g.result(true))
    }

    @Test fun lateSuccessAfterTimeoutIsStillApplied() {
        val g = AuthGate()
        g.begin(0); g.expire()
        assertTrue(g.result(true))
        assertTrue(g.authorized)
    }

    /** Review hub-wp2 r5 P1: the origin lives with the attempt; a user open joining it takes it over. */
    @Test fun theAttemptKeepsItsOriginAndAUserOpenTakesItOver() {
        val g = AuthGate()
        assertTrue(g.begin(0, automatic = true)); assertTrue(g.automatic)
        assertFalse(g.begin(1, automatic = true)); assertTrue(g.automatic)
        assertFalse(g.begin(2, automatic = false)); assertFalse(g.automatic, "the wearer's open joined the attempt")
    }

    @Test fun anExpiredAttemptIsMarkedLateUntilTheNextOne() {
        val g = AuthGate()
        g.begin(0, automatic = true); g.expire()
        assertTrue(g.expired); assertTrue(g.automatic)
        assertTrue(g.result(true), "the late success is accepted (the token is kept)")
        g.begin(1, automatic = true)
        assertFalse(g.expired)
    }
}
