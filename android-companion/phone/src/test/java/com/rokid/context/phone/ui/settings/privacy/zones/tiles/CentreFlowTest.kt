package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.settings.privacy.zones.LatLon
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Centre on me (design S2) with a fake [CentreFix]: permission denied, no fix, success, a fresh known fix and Cancel. */
@OptIn(ExperimentalCoroutinesApi::class)
class CentreFlowTest {
    private val here = LatLon(10.0, 20.0)

    @Test fun deniedAsksOnceOnTheTapAndNeverRequestsAFix() = runTest {
        var asked = 0; var fixes = 0
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), { fixes++; here }, { false }, { asked++ })
        f.tap()
        assertEquals(1, asked); assertEquals(0, fixes)
        assertEquals(CentreWords.ALLOW, f.state.value.note); assertFalse(f.state.value.finding); assertNull(f.state.value.request)
    }

    @Test fun noFixShowsTheNoFixNote() = runTest {
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), { null }, { true }, {})
        f.tap()
        assertEquals(OnlineMapWords.NO_FIX, f.state.value.note); assertFalse(f.state.value.finding); assertNull(f.state.value.request)
    }

    @Test fun successCentresAfterFindingYou() = runTest {
        val gate = CompletableDeferred<LatLon?>()
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), { gate.await() }, { true }, {})
        f.tap()
        assertTrue(f.state.value.finding); assertNull(f.state.value.request)
        f.tap() // a second tap while finding does nothing
        gate.complete(here)
        assertFalse(f.state.value.finding); assertEquals(here, f.state.value.request?.at); assertNull(f.state.value.note)
    }

    @Test fun cancelStopsTheRequestAndShowsNothing() = runTest {
        var cancelled = false
        val gate = CompletableDeferred<LatLon?>()
        val fix = CentreFix { try { gate.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e } }
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), fix, { true }, {})
        f.tap(); assertTrue(f.state.value.finding)
        f.cancel()
        assertTrue(cancelled); assertFalse(f.state.value.finding); assertNull(f.state.value.note); assertNull(f.state.value.request)
    }

    @Test fun aFreshKnownFixIsUsedWithoutPermissionOrNewFix() = runTest {
        var asked = 0; var fixes = 0
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), { fixes++; null }, { false }, { asked++ }, { here })
        f.tap()
        assertEquals(0, asked); assertEquals(0, fixes); assertNotNull(f.state.value.request)
    }

    @Test fun aThrowingFixIsNoFix() = runTest {
        val f = CentreFlow(TestScope(UnconfinedTestDispatcher(testScheduler)), { error("boom") }, { true }, {})
        f.tap()
        assertEquals(OnlineMapWords.NO_FIX, f.state.value.note); assertFalse(f.state.value.finding)
    }

    @Test fun offlineUsesGpsForTwentySecondsOnlineBalancedForTwelve() {
        assertEquals(20_000L, com.rokid.context.phone.ui.settings.privacy.zones.online.CentreTiming.timeoutMs(false))
        assertEquals(12_000L, com.rokid.context.phone.ui.settings.privacy.zones.online.CentreTiming.timeoutMs(true))
        assertTrue(com.rokid.context.phone.ui.settings.privacy.zones.online.CentreTiming.priority(false) != com.rokid.context.phone.ui.settings.privacy.zones.online.CentreTiming.priority(true))
    }
}
