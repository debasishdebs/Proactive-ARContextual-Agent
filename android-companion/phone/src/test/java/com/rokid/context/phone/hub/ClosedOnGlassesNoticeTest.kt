package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.hub.ClosedOnGlassesNotice.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** NOT1 (R11): one silent "Open on glasses" notification per closed episode, after 45 s of CLOSED_ON_GLASSES without a break. */
class ClosedOnGlassesNoticeTest {
    private var t = 1_000_000L
    private val n = ClosedOnGlassesNotice { t }

    @Test fun showsAfterFortyFiveSecondsClosed() {
        assertEquals(Action.None, n.onState(LinkState.CLOSED_ON_GLASSES))
        t += ClosedOnGlassesNotice.DELAY_MS - 1; assertEquals(Action.None, n.onTick())
        t += 1; assertEquals(Action.Show, n.onTick())
        t += 5_000; assertEquals("only once", Action.None, n.onTick())
    }

    @Test fun autoReopenBeforeDelayNeverShows() {
        n.onState(LinkState.CLOSED_ON_GLASSES)
        t += 8_000 // the relauncher's first reopen
        assertNotEquals(Action.Show, n.onState(LinkState.CONNECTED))
        t += 120_000
        assertEquals(Action.None, n.onTick())
    }

    @Test fun cancelsOnAnyOtherState() {
        for (other in LinkState.entries.filter { it != LinkState.CLOSED_ON_GLASSES }) {
            n.onState(LinkState.CLOSED_ON_GLASSES); t += ClosedOnGlassesNotice.DELAY_MS
            assertEquals(Action.Show, n.onTick())
            assertEquals("$other", Action.Cancel, n.onState(other))
        }
    }

    @Test fun onceperEpisodeAfterDismiss() {
        n.onState(LinkState.CLOSED_ON_GLASSES); t += ClosedOnGlassesNotice.DELAY_MS; assertEquals(Action.Show, n.onTick())
        n.onDismissed()
        t += 600_000
        assertEquals(Action.None, n.onTick()); assertEquals(Action.None, n.onState(LinkState.CLOSED_ON_GLASSES)); assertEquals(Action.None, n.onTick())
        // the same after the action was used, even when the tap came before the delay
        n.onState(LinkState.CONNECTED); n.onState(LinkState.CLOSED_ON_GLASSES); t += 10_000
        n.onOpened(); t += 600_000
        assertEquals(Action.None, n.onTick())
    }

    @Test fun newEpisodeShowsAgain() {
        n.onState(LinkState.CLOSED_ON_GLASSES); t += ClosedOnGlassesNotice.DELAY_MS; assertEquals(Action.Show, n.onTick())
        n.onDismissed(); n.onState(LinkState.CONNECTED)
        t += 1_000; n.onState(LinkState.CLOSED_ON_GLASSES)
        t += ClosedOnGlassesNotice.DELAY_MS - 1; assertEquals(Action.None, n.onTick())
        t += 1; assertEquals(Action.Show, n.onTick())
    }

    @Test fun repeatedClosedStateDoesNotRestartTheClock() {
        n.onState(LinkState.CLOSED_ON_GLASSES); t += 30_000
        n.onState(LinkState.CLOSED_ON_GLASSES); t += 15_000
        assertEquals(Action.Show, n.onTick())
    }

    /** One delayed job on the closed edge, cancelled by any other state: no repeating tick while the service lives. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun driverUsesOneDelayedJobNotATick() = kotlinx.coroutines.test.runTest {
        val actions = mutableListOf<Action>()
        val d = ClosedNoticeDriver(this, ClosedOnGlassesNotice { testScheduler.currentTime }, { if (it != Action.None) actions += it })
        d.onLink(LinkState.CONNECTED); testScheduler.advanceTimeBy(600_000); assertEquals("nothing runs while connected", listOf(Action.Cancel), actions)
        actions.clear()
        d.onLink(LinkState.CLOSED_ON_GLASSES)
        testScheduler.advanceTimeBy(ClosedOnGlassesNotice.DELAY_MS - 1); testScheduler.runCurrent(); assertEquals(emptyList<Action>(), actions)
        testScheduler.advanceTimeBy(2); testScheduler.runCurrent(); assertEquals(listOf(Action.Show), actions)
        testScheduler.advanceUntilIdle() // nothing left scheduled: the job ended with the Show
        assertEquals(listOf(Action.Show), actions)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun driverCancelsTheJobWhenTheLinkLeavesClosed() = kotlinx.coroutines.test.runTest {
        val actions = mutableListOf<Action>()
        val d = ClosedNoticeDriver(this, ClosedOnGlassesNotice { testScheduler.currentTime }, { if (it != Action.None) actions += it })
        d.onLink(LinkState.CLOSED_ON_GLASSES); testScheduler.advanceTimeBy(8_000); d.onLink(LinkState.CONNECTED)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(Action.Cancel), actions)
        d.onLink(LinkState.CLOSED_ON_GLASSES); d.onLink(LinkState.CLOSED_ON_GLASSES) // a repeated state does not start a second job
        testScheduler.advanceUntilIdle(); assertEquals(listOf(Action.Cancel, Action.Show), actions)
    }
}
