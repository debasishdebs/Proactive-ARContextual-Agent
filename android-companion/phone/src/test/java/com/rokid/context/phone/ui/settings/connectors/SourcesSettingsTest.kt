package com.rokid.context.phone.ui.settings.connectors

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.sources.SourceKind
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class FakeSourcesControl(var grant: Boolean = false) : SourcesControl {
    override val enabled = MutableStateFlow<Set<SourceKind>>(emptySet())
    override val nudgesPerDay = MutableStateFlow(6)
    override fun set(k: SourceKind, on: Boolean) { enabled.value = if (on) enabled.value + k else enabled.value - k }
    override fun setNudgesPerDay(n: Int) { nudgesPerDay.value = n }
    override fun granted(k: SourceKind) = grant || k == SourceKind.NOTES
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SourcesSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun enableRequestsPermissionThenStaysOffWhenDenied() = runBlocking {
        val control = FakeSourcesControl()
        val vm = SourcesViewModel(control, Dispatchers.Unconfined)
        try {
            val asked = mutableListOf<List<String>>()
            vm.onToggle(SourceKind.CALENDAR, true) { asked += it }
            assertEquals(listOf(listOf(android.Manifest.permission.READ_CALENDAR)), asked)
            assertEquals("not on before the answer", emptySet<SourceKind>(), control.enabled.value)

            vm.onPermissionResult(granted = false, canAskAgain = true)
            assertEquals(emptySet<SourceKind>(), control.enabled.value)
            assertEquals(emptySet<SourceKind>(), withTimeout(5_000) { vm.state.first { !it.loading } }.needsPermission)

            vm.onToggle(SourceKind.CALENDAR, true) {}
            vm.onPermissionResult(granted = false, canAskAgain = false)
            assertEquals(emptySet<SourceKind>(), control.enabled.value)
            assertEquals(setOf(SourceKind.CALENDAR), withTimeout(5_000) { vm.state.first { it.needsPermission.isNotEmpty() } }.needsPermission)

            control.grant = true
            vm.onToggle(SourceKind.CALENDAR, true) {}
            vm.onPermissionResult(granted = true, canAskAgain = true)
            assertEquals(setOf(SourceKind.CALENDAR), control.enabled.value)
        } finally { vm.cancelScope() }
    }

    @Test fun notesNeedNoPermissionAndARevokeShowsPermissionNeeded() = runBlocking {
        val control = FakeSourcesControl()
        val vm = SourcesViewModel(control, Dispatchers.Unconfined)
        try {
            vm.onToggle(SourceKind.NOTES, true) { error("no request") }
            assertEquals(setOf(SourceKind.NOTES), control.enabled.value)
            control.enabled.value = control.enabled.value + SourceKind.LOCATION // on, then revoked in system settings
            vm.onResume()
            assertEquals(setOf(SourceKind.LOCATION), withTimeout(5_000) { vm.state.first { it.needsPermission.isNotEmpty() } }.needsPermission)
        } finally { vm.cancelScope() }
    }

    @Test fun nudgesPerDayZeroShowsOff() {
        compose.setContent { PcTheme { SourcesScreen(SourcesUiState(loading = false, nudgesPerDay = 0), onBack = {}, onToggle = { _, _ -> }, onNudgesPerDay = {}, onOpenAppSettings = {}) } }
        compose.onNodeWithTag("sources").performScrollToNode(hasTestTag("nudges_per_day"))
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithTag("sources").performScrollToNode(hasTestTag("sources_footer"))
        compose.onNodeWithText("Used only on this phone to answer and nudge. Never sent to your glasses.").assertIsDisplayed()
    }

    @Test fun aBlockedSourceOffersOpenSettings() {
        compose.setContent {
            PcTheme { SourcesScreen(SourcesUiState(loading = false, needsPermission = setOf(SourceKind.CONTACTS)), onBack = {}, onToggle = { _, _ -> }, onNudgesPerDay = {}, onOpenAppSettings = {}) }
        }
        compose.onNodeWithText("Permission needed \u00b7 Open settings").assertIsDisplayed()
    }

    /** The three timing rows show their defaults, report a pick, and the store keeps it across a restart. */
    @Test fun nudgeTimingRowsPersist() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = ctx.getSharedPreferences("nudge_timing_test", android.content.Context.MODE_PRIVATE)
        val store = com.rokid.context.connectors.sources.SourceSettingsStore(prefs)
        assertEquals(listOf(5, 30, 30), listOf(store.settleSec.value, store.checkGapSec.value, store.shownGapMin.value))

        val state = mutableStateOf(SourcesUiState(loading = false))
        compose.setContent {
            PcTheme {
                SourcesScreen(
                    state.value, onBack = {}, onToggle = { _, _ -> }, onNudgesPerDay = {}, onOpenAppSettings = {},
                    onSettleSec = { store.setSettleSec(it) }, onCheckGapSec = { store.setCheckGapSec(it) }, onShownGapMin = { store.setShownGapMin(it) },
                )
            }
        }
        for ((row, idx) in listOf("nudge_settle" to 2, "nudge_check_gap" to 1, "nudge_shown_gap" to 2)) {
            compose.onNodeWithTag("sources").performScrollToNode(hasTestTag(row))
            compose.onNodeWithTag(row).assertIsDisplayed().performClick()
            compose.onNodeWithTag("${row}_$idx").performClick()
        }
        store.setSettleSec(7) // not an offered value: ignored
        val reopened = com.rokid.context.connectors.sources.SourceSettingsStore(prefs)
        assertEquals(listOf(10, 60, 60), listOf(reopened.settleSec.value, reopened.checkGapSec.value, reopened.shownGapMin.value))
    }
}
