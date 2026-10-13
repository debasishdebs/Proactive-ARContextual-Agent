package com.rokid.context.phone.ui.shell

import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.activity.Period
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test fun settingsRoutesBuildAndBelongToTheSettingsTab() {
        assertEquals("settings/endpoints/az%201", Routes.endpoint("az 1"))
        assertEquals("settings/prompts/ANSWER/history", Routes.promptHistory("ANSWER"))
        listOf(Routes.SETTINGS_GLASSES, Routes.SETTINGS_ENDPOINT, Routes.SETTINGS_PROMPT_HISTORY, Routes.DIAGNOSTICS_LOGS)
            .forEach { assertEquals(it, Routes.Tab.SETTINGS, Routes.tabFor(it)) }
        assertTrue(Routes.SETTINGS_GLASSES in Routes.LIVE_SETTINGS)
    }

    @Test fun tabsFollowTheRoutePattern() {
        assertEquals(Routes.Tab.HOME, Routes.tabFor(Routes.HOME))
        assertEquals(Routes.Tab.HOME, Routes.tabFor(Routes.ASK))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.ACTIVITY))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.DAY))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor(Routes.MEMORY))
        assertEquals(Routes.Tab.SETTINGS, Routes.tabFor(Routes.DIAGNOSTICS_TRACKER))
        assertNull(Routes.tabFor(Routes.ONBOARDING))
        assertNull(Routes.tabFor(null))
    }

    @Test fun pillNavIsHiddenOnOnboardingAndTheViewer() {
        assertFalse(Routes.showsNav(Routes.ONBOARDING))
        assertFalse(Routes.showsNav(Routes.FRAMES))
        assertFalse(Routes.showsNav(null))
        assertTrue(Routes.showsNav(Routes.HOME))
        assertTrue(Routes.showsNav(Routes.SETTINGS_ENDPOINTS))
    }

    @Test fun buildersEncodeArguments() {
        assertEquals("ask/a%20b%2Fc", Routes.ask("a b/c"))
        assertEquals("activity", Routes.activity())
        assertEquals("activity?period=week", Routes.activity(Period.WEEK))
        assertEquals("day/2026-10-12", Routes.day(TODAY))
        assertEquals("frames/2026-10-12", Routes.frames(TODAY))
        assertEquals("frames/2026-10-12?start=f%201", Routes.frames(TODAY, "f 1"))
        assertEquals(Routes.ONBOARDING, Routes.startRoute(onboardingComplete = false))
        assertEquals(Routes.HOME, Routes.startRoute(onboardingComplete = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tickerEmitsImmediatelyThenEverySecond() = runTest {
        assertEquals(listOf(0L, 1_000L, 2_000L), PhoneDeps.ticker(now = { currentTime }).take(3).toList())
    }

    /** Carried from Phase 3: the hub needs BLUETOOTH_CONNECT, a runtime permission only from API 31 (S). */
    @Test fun bluetoothConnectIsARuntimeRequestOnlyFromApi31() {
        assertFalse(BluetoothPermission.isMissing(sdkInt = 30, granted = false))
        assertTrue(BluetoothPermission.isMissing(sdkInt = 31, granted = false))
        assertFalse(BluetoothPermission.isMissing(sdkInt = 34, granted = true))
    }
}
