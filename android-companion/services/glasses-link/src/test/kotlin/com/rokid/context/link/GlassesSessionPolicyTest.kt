package com.rokid.context.link

import com.rokid.context.core.model.LinkState
import com.rokid.cxr.session.SessionErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassesSessionPolicyTest {
    @Test fun startedSendsSettingsAndMarksConnected() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Started))
    }

    /** BT drop → CXR pauses the session and resumes it by itself (phase 0): keep it, no second session. */
    @Test fun pausedSessionIsKeptAndResumeResendsSettings() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkPaused), p.onEvent(LinkEvent.Paused))
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Resumed))
    }

    @Test fun closedWhilePresentRetriesWithBackoffAndNeverDoubleConnects() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
        assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "connect already in flight")
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(5_000)), p.onEvent(LinkEvent.ConnectFailed))
        p.onEvent(LinkEvent.RetryTimer); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed), "backoff reset after success")
    }

    @Test fun deviceGoneStopsRetrying() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        p.onEvent(LinkEvent.Closed)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.DeviceGone))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun manualConnectReplacesAStuckConnectingAttemptButNotAnOpenSession() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
        p.onEvent(LinkEvent.Started)
        assertTrue(p.manualConnect().isEmpty())
        p.onEvent(LinkEvent.Paused)
        assertTrue(p.manualConnect().isEmpty())
    }

    @Test fun connectTimeoutBehavesLikeFailureOnlyWhileConnecting() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "nothing in flight")
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.ConnectTimeout))
        assertTrue(p.onEvent(LinkEvent.ConnectTimeout).isEmpty(), "already waiting to retry")
    }

    /** Spec §1.7: GLASSES_EXIT never auto-reconnects (it would reopen the activity and steal the foreground). */
    @Test fun glassesExitDoesNotAutoRetryUntilManualConnect() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkMinimised), p.onEvent(LinkEvent.GlassesExited))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        p.onEvent(LinkEvent.BackgroundSilent) // a real exit (review I2: a minimised app is never manually connected)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
    }

    @Test fun linkLossStillRetriesWithBackoff() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
    }

    @Test fun authFailureStopsAutoRetry() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkAuthNeeded), p.onEvent(LinkEvent.AuthFailed))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun autoConnectTriesOnceWithoutARetryLoop() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.ConnectFailed), "no ScheduleRetry")
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun autoConnectLeavesAnAttemptInFlightOrAnOpenSessionAlone() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        assertTrue(p.autoConnect().isEmpty(), "a connect is already in flight")
        p.onEvent(LinkEvent.Started)
        assertTrue(p.autoConnect().isEmpty(), "open session")
    }

    /** The user reopened the app on the glasses after closing it there: that is consent, so reconnect. */
    @Test fun glassesAppOpenedReconnectsOnlyAfterAClosedOnGlasses() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "never closed on glasses")
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "session open")
        p.onEvent(LinkEvent.GlassesExited)
        assertTrue(p.backgroundLink)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.GlassesAppOpened))
        assertFalse(p.backgroundLink)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty(), "connect already in flight")
    }

    @Test fun closedOnGlassesEndsOnDeviceGoneOrManualConnect() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        p.onEvent(LinkEvent.DeviceGone)
        assertFalse(p.backgroundLink)
        assertTrue(p.onEvent(LinkEvent.GlassesAppOpened).isEmpty())
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited); p.onEvent(LinkEvent.BackgroundSilent)
        p.manualConnect()
        assertFalse(p.backgroundLink)
    }

    @Test fun closedOnGlassesStillAllowsAHubStartAttempt() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited); p.onEvent(LinkEvent.BackgroundSilent)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
    }

    /** Minimised Option A: GLASSES_EXIT keeps a background data link; nothing automatic may start the HUD (appStart). */
    @Test fun glassesExitMinimisesOnTheBackgroundLinkAndNeverAutoConnects() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkMinimised), p.onEvent(LinkEvent.GlassesExited))
        assertTrue(p.backgroundLink)
        assertTrue(p.autoConnect().isEmpty(), "a hub start must not bring the HUD forward")
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        assertTrue(p.onEvent(LinkEvent.DevicePresent).isEmpty())
        assertTrue(p.onEvent(LinkEvent.BackgroundAlive).isEmpty(), "already minimised")
    }

    /** A real exit: the glasses never answer the background link, so the phone says "closed on glasses" and keeps watching. */
    @Test fun silentBackgroundLinkFallsBackToClosedOnGlassesAndAMessageMinimisesAgain() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        assertEquals(listOf(LinkAction.MarkClosedOnGlasses), p.onEvent(LinkEvent.BackgroundSilent))
        assertTrue(p.backgroundLink, "still bound: watches for a reopen and for a late glasses message")
        assertTrue(p.onEvent(LinkEvent.BackgroundSilent).isEmpty())
        assertEquals(listOf(LinkAction.MarkMinimised), p.onEvent(LinkEvent.BackgroundAlive))
    }

    /** Hand-back: our app back in front on the glasses returns to the normal CUSTOM_APP session path. */
    @Test fun reopeningOurAppWhileMinimisedReconnectsTheSession() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.GlassesAppOpened))
        assertFalse(p.backgroundLink)
        assertTrue(p.onEvent(LinkEvent.BackgroundAlive).isEmpty(), "a late background message changes nothing")
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Started))
    }

    /** Review I2: a CUSTOM_APP connect runs appStart, which would pull the HUD over Maps. While minimised nothing may. */
    @Test fun manualConnectWhileMinimisedNeverStartsTheGlassesApp() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started); p.onEvent(LinkEvent.GlassesExited)
        assertTrue(p.manualConnect().isEmpty(), "Open on glasses / an auth result must not appStart")
        assertTrue(p.minimised)
        assertTrue(p.backgroundLink, "the background link stays up")
        p.onEvent(LinkEvent.BackgroundSilent)
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect(), "a real exit may be reopened from the phone")
    }

    @Test fun backgroundEventsOutsideTheBackgroundLinkAreIgnored() {
        val p = GlassesSessionPolicy()
        assertTrue(p.onEvent(LinkEvent.BackgroundAlive).isEmpty())
        assertTrue(p.onEvent(LinkEvent.BackgroundSilent).isEmpty())
        p.manualConnect(); p.onEvent(LinkEvent.Started)
        assertTrue(p.onEvent(LinkEvent.BackgroundAlive).isEmpty())
        assertTrue(p.onEvent(LinkEvent.BackgroundSilent).isEmpty())
    }

    @Test fun onlyConnectedUsesTheSessionAndOnlyMinimisedTheBackgroundLink() {
        assertEquals(SendRoute.SESSION, sendRoute(LinkState.CONNECTED))
        assertEquals(SendRoute.BACKGROUND, sendRoute(LinkState.MINIMISED))
        for (s in LinkState.entries - LinkState.CONNECTED - LinkState.MINIMISED) assertEquals(SendRoute.NONE, sendRoute(s), "$s")
    }

    /** Spec §1.7: phone-app resume is not an explicit action, so a close on the glasses is never undone by it. */
    @Test fun resumeReconnectsOnlyAnAuthorizedOfflineLink() {
        assertTrue(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = true, authDeclined = false))
        assertFalse(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = false, authDeclined = false), "never authorized")
        assertFalse(shouldReconnectOnResume(LinkState.OFFLINE, hasToken = true, authDeclined = true), "declined")
        assertFalse(shouldReconnectOnResume(LinkState.CLOSED_ON_GLASSES, hasToken = true, authDeclined = false), "closed on glasses")
        for (s in listOf(LinkState.CONNECTING, LinkState.CONNECTED, LinkState.PAUSED_BT, LinkState.NOT_AUTHORIZED, LinkState.VERSION_MISMATCH)) {
            assertFalse(shouldReconnectOnResume(s, hasToken = true, authDeclined = false), "$s")
        }
    }

    @Test fun markActionsMapToLinkStates() {
        assertEquals(LinkState.CONNECTED, LinkAction.MarkConnected.linkState())
        assertEquals(LinkState.CONNECTING, LinkAction.MarkConnecting.linkState())
        assertEquals(LinkState.PAUSED_BT, LinkAction.MarkPaused.linkState())
        assertEquals(LinkState.OFFLINE, LinkAction.MarkDisconnected.linkState())
        assertEquals(LinkState.CLOSED_ON_GLASSES, LinkAction.MarkClosedOnGlasses.linkState())
        assertEquals(LinkState.MINIMISED, LinkAction.MarkMinimised.linkState())
        assertEquals(LinkState.NOT_AUTHORIZED, LinkAction.MarkAuthNeeded.linkState())
        assertNull(LinkAction.Connect.linkState())
        assertNull(LinkAction.ScheduleRetry(1).linkState())
    }

    /** close() must leave the policy Idle: a hub stop/start in one process has to reconnect. */
    @Test fun resetReturnsToIdleSoTheNextHubStartConnects() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Started)
        assertTrue(p.autoConnect().isEmpty(), "open session")
        p.reset()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
    }

    @Test fun resetCancelsAPendingRetry() {
        val p = GlassesSessionPolicy()
        p.manualConnect(); p.onEvent(LinkEvent.Closed)
        p.reset()
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        assertFalse(p.backgroundLink)
    }

    @Test fun refusedConnectIsTerminalUntilAnExplicitAction() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.ConnectRefused))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect())
    }

    @Test fun transientRetriesStopAfterFiveAtTheCeiling() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        val delays = mutableListOf<Long>()
        repeat(8) {
            val a = p.onEvent(LinkEvent.ConnectFailed)
            delays += (a.last() as LinkAction.ScheduleRetry).delayMs
            assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
        }
        assertEquals(listOf(2_000L, 5_000, 10_000, 30_000, 30_000, 30_000, 30_000, 30_000), delays)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.ConnectFailed), "cap reached: no ScheduleRetry")
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "no further Connect")
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect(), "explicit connect works")
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.ConnectFailed), "budget renewed")
    }

    @Test fun aStartedSessionRenewsTheRetryBudget() {
        val p = GlassesSessionPolicy()
        p.manualConnect()
        repeat(6) { p.onEvent(LinkEvent.ConnectFailed); p.onEvent(LinkEvent.RetryTimer) }
        p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
    }

    @Test fun nonTransientSdkCodesAreTerminal() {
        for (c in listOf(SessionErrorCode.ROKID_APP_NOT_INSTALLED, SessionErrorCode.ROKID_APP_VERSION_LOW, SessionErrorCode.BT_NOT_CONNECTED,
            SessionErrorCode.GLASSES_APP_NOT_FOUND, SessionErrorCode.GLASSES_APP_INSTALL_FAILED)) assertTrue(isTerminalConnectError(c), "$c")
        for (c in listOf(SessionErrorCode.LINK_LOST, SessionErrorCode.LINK_TIMEOUT, SessionErrorCode.CONNECT_FAILED, null)) assertFalse(isTerminalConnectError(c), "$c")
    }

    /** An auth timeout (blocked background start) is not a decline: a resume with a token retries. */
    @Test fun resumeRetriesAnAuthTimeoutButNotADecline() {
        assertTrue(shouldReconnectOnResume(LinkState.NOT_AUTHORIZED, hasToken = true, authDeclined = false, authTimedOut = true))
        assertFalse(shouldReconnectOnResume(LinkState.NOT_AUTHORIZED, hasToken = true, authDeclined = true, authTimedOut = true))
        assertFalse(shouldReconnectOnResume(LinkState.NOT_AUTHORIZED, hasToken = false, authDeclined = false, authTimedOut = true))
        assertFalse(shouldReconnectOnResume(LinkState.NOT_AUTHORIZED, hasToken = true, authDeclined = false, authTimedOut = false))
    }

    /**
     * BUG-R2-4: a cold start whose Hi Rokid authorization never reported back (timeout, not a decline) retries
     * automatically 2 / 5 / 10 s later, showing Connecting, then gives up as NOT_AUTHORIZED.
     */
    @Test fun authTimeoutRetriesWithBackoffThenGivesUp() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.autoConnect())
        for (delay in listOf(2_000L, 5_000L, 10_000L)) {
            assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.ScheduleRetry(delay)), p.onEvent(LinkEvent.AuthTimedOut))
            assertTrue(p.waitingAuthRetry)
            assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
            assertFalse(p.waitingAuthRetry)
        }
        assertEquals(listOf(LinkAction.MarkAuthNeeded), p.onEvent(LinkEvent.AuthTimedOut))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "gave up: no further attempt")
    }

    /** BUG-R2-4: a real verdict from the SDK (declined / failed) is never retried, even mid-backoff. */
    @Test fun aRealAuthFailureStopsTheRetries() {
        val p = GlassesSessionPolicy()
        p.autoConnect()
        p.onEvent(LinkEvent.AuthTimedOut)
        assertEquals(listOf(LinkAction.MarkAuthNeeded), p.onEvent(LinkEvent.AuthFailed))
        assertFalse(p.waitingAuthRetry)
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    /** BUG-R2-4: a session that starts, or a manual Reconnect, gives the next cold start its full retry budget. */
    @Test fun theAuthRetryBudgetResetsOnSuccessAndOnManualConnect() {
        val p = GlassesSessionPolicy()
        p.autoConnect()
        p.onEvent(LinkEvent.AuthTimedOut); p.onEvent(LinkEvent.RetryTimer)
        p.onEvent(LinkEvent.AuthTimedOut); p.onEvent(LinkEvent.RetryTimer)
        p.onEvent(LinkEvent.Started)
        p.reset(); p.autoConnect()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.AuthTimedOut))
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.manualConnect(), "a manual connect replaces the wait")
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.AuthTimedOut))
    }

    /** BUG-R2-4: the glasses going away cancels a pending auth retry. */
    @Test fun deviceGoneCancelsAPendingAuthRetry() {
        val p = GlassesSessionPolicy()
        p.autoConnect()
        p.onEvent(LinkEvent.AuthTimedOut)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.DeviceGone))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    /** BUG-R2-4: bringing the phone app forward during the wait retries at once (a foreground launch is allowed). */
    @Test fun resumeRetriesAtOnceWhileAnAuthRetryIsPending() {
        assertTrue(shouldReconnectOnResume(LinkState.CONNECTING, hasToken = true, authDeclined = false, authRetryPending = true))
        assertFalse(shouldReconnectOnResume(LinkState.CONNECTING, hasToken = true, authDeclined = false))
        assertFalse(shouldReconnectOnResume(LinkState.CONNECTING, hasToken = true, authDeclined = true, authRetryPending = true))
    }
}
