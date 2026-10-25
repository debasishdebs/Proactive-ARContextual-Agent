package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.QUICK_LOOK_MS_ALLOWED
import com.rokid.context.core.model.REST_MINUTES_ALLOWED
import com.rokid.context.core.model.WORK_MINUTES_ALLOWED
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionLabel
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.FakeHudSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class CaptureSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun intervalIsClampedToThirtySecondsToOneHour() {
        assertEquals(30, CaptureIntervals.clamp(0)); assertEquals(30, CaptureIntervals.clamp(-5))
        assertEquals(3_600, CaptureIntervals.clamp(7_200)); assertEquals(420, CaptureIntervals.clamp(420))
        assertEquals("30 s", CaptureIntervals.label(30)); assertEquals("5 min", CaptureIntervals.label(300)); assertEquals("1 h", CaptureIntervals.label(3_600))
        assertEquals((CaptureIntervals.PRESETS_SEC + 420).sorted(), CaptureIntervals.options(420))
    }

    @Test fun anOutOfRangeStoredIntervalShowsAndSavesClamped() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureIntervalSec = 7_200))
        val vm = CaptureSettingsViewModel(hud)
        assertEquals(3_600, withTimeout(5_000) { vm.state.first() }.intervalSec)
        vm.setInterval(5); assertEquals(30, hud.settings.value.captureIntervalSec)
        vm.setInterval(600); assertEquals(600, hud.settings.value.captureIntervalSec)
        vm.cancelScope()
    }

    private var used = 0
    private fun show(state: CaptureSettingsUiState, vm: CaptureSettingsViewModel? = null) {
        compose.setContent { PcTheme { CaptureSettingsScreen(state, onBack = {}, onModeChoice = { vm?.setModeChoice(it) }, onBatteryTarget = { vm?.setBatteryTarget(it) }, onPauseWhenPhoneInUse = { vm?.setPauseWhenPhoneInUse(it) }, onWork = { vm?.setWork(it) }, onRest = { vm?.setRest(it) }, onWorkRestEnabled = { vm?.setWorkRestEnabled(it) }, onMotionLabel = { vm?.setMotionLabel(it) }, onUseSettings = { vm?.useSettings(); used++ }) } }
    }

    @Test fun smartHidesInterval() {
        show(CaptureSettingsUiState(mode = CaptureMode.SMART, wake = CameraWake.MOTION))
        compose.onNodeWithTag("interval").assertDoesNotExist()
        compose.onNodeWithContentDescription("Capture mode: Smart").assertExists()
    }

    @Test fun fixedShowsInterval() {
        show(CaptureSettingsUiState(mode = CaptureMode.FIXED, intervalSec = 300))
        compose.onNodeWithContentDescription("Every: 5 min").assertExists()
    }

    @Test fun burstSwitchGone() {
        show(CaptureSettingsUiState(mode = CaptureMode.FIXED))
        compose.onNodeWithTag("burst").assertDoesNotExist()
        compose.onNodeWithText("Take an extra photo when the scene changes", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Burst on change", substring = true).assertDoesNotExist()
    }

    @Test fun quickLookOptionsAndDefault() {
        assertEquals(1_000, HudSettings().quickLookMs)
        assertEquals(listOf("1 s", "1.5 s", "2 s", "3 s"), QUICK_LOOK_MS_ALLOWED.map(CaptureLabels::quickLook))
        show(CaptureSettingsUiState(wake = CameraWake.MOTION))
        compose.onNodeWithContentDescription("Quick look: 1 s").assertExists()
        compose.onNodeWithText("Shorter catches quicker glances; the camera light is on more.").assertExists()
    }

    @Test fun workRestOptions() = runBlocking {
        assertEquals(listOf(30, 40, 45, 50, 60, 75, 90), WORK_MINUTES_ALLOWED)
        assertEquals(listOf(5, 10, 15, 20), REST_MINUTES_ALLOWED)
        val s = withTimeout(5_000) { CaptureSettingsViewModel(FakeHudSettings()).also { it.cancelScope() }.state.first() }
        assertEquals(45, s.workMinutes); assertEquals(15, s.restMinutes)
        val hud = FakeHudSettings(HudSettings(workMinutes = 45, restMinutes = 5))
        val vm = CaptureSettingsViewModel(hud)
        vm.setWork(60)
        assertEquals(60, hud.settings.value.workMinutes); assertEquals(15, hud.settings.value.restMinutes)
        vm.setWork(30); vm.setRest(5)
        assertEquals(5, hud.settings.value.restMinutes)
        vm.cancelScope()
    }

    @Test fun restBelow15DisabledWhenWorkOver45() {
        show(CaptureSettingsUiState(workRestEnabled = true, workMinutes = 60, restMinutes = 15))
        compose.onNodeWithText("Rest is at least 15 min when Work is over 45 min.").assertExists()
        compose.onNodeWithTag("rest").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("rest_0").assertIsNotEnabled()
        compose.onNodeWithTag("rest_1").assertIsNotEnabled()
        compose.onNodeWithTag("rest_2").assertIsEnabled()
        compose.onNodeWithTag("rest_3").assertIsEnabled()
    }

    @Test fun restOptionsEnabledWhenWorkIs45OrLess() {
        show(CaptureSettingsUiState(workRestEnabled = true, workMinutes = 45))
        compose.onNodeWithTag("rest").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("rest_0").assertIsEnabled()
        compose.onNodeWithTag("rest_note").assertDoesNotExist()
    }

    @Test fun windowToleranceDefaultsTo90PersistsAndIsClamped() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("tol_test", 0).also { it.edit().clear().commit() }
        val store = WindowToleranceStore(prefs)
        assertEquals(90, store.pct.value)
        val vm = CaptureSettingsViewModel(FakeHudSettings(), tolerance = store)
        vm.setWindowTolerance(75)
        assertEquals(75, prefs.getInt("window_min_valid_pct", 0))
        assertEquals(75, WindowToleranceStore(prefs).pct.value)
        assertEquals(75, withTimeout(5_000) { vm.state.first { it.windowMinValidPct == 75 } }.windowMinValidPct)
        store.set(10); assertEquals(50, store.pct.value); store.set(500); assertEquals(100, store.pct.value)
        assertEquals(listOf(50, 60, 70, 75, 80, 90, 95, 100), WindowToleranceStore.options(75))
        vm.cancelScope()
    }

    @Test fun windowToleranceRowIsShownAndSetsThePercent() {
        var picked = -1
        compose.setContent { PcTheme { CaptureSettingsScreen(CaptureSettingsUiState(windowMinValidPct = 90), onBack = {}, onWindowTolerance = { picked = it }) } }
        compose.onNodeWithTag("window_tolerance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("window_tolerance").performClick()
        compose.onNodeWithTag("window_tolerance_2").performClick()   // 50, 60, 70
        assertEquals(70, picked)
    }

    @Test fun pacePersists() = runBlocking {
        val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("pace_test", 0).also { it.edit().clear().commit() }
        val store = AiCallPaceStore(prefs)
        assertEquals(12_000L, store.paceMs.value)
        val vm = CaptureSettingsViewModel(FakeHudSettings(), store)
        vm.setPace(15_000)
        assertEquals(15_000L, prefs.getLong("ai_call_pace_ms", 0))
        assertEquals(15_000L, AiCallPaceStore(prefs).paceMs.value)
        assertEquals(15_000L, withTimeout(5_000) { vm.state.first { it.paceMs == 15_000L } }.paceMs)
        store.set(11_000); assertEquals(10_000L, store.paceMs.value)
        vm.cancelScope()
    }

    /** Fix wave H: the wear switch is back (real signal, default on), and the capture blink is a synced setting, default on. */
    @Test fun wearSwitchAndCaptureBlinkSync() = runBlocking {
        show(CaptureSettingsUiState())
        compose.onNodeWithTag("pause_not_worn").assertExists()
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        assertTrue(hud.settings.value.captureCue); assertTrue(hud.settings.value.pauseWhenNotWorn)
        vm.setCaptureCue(false); assertFalse(hud.settings.value.captureCue)
        vm.setPauseWhenNotWorn(false); assertFalse(hud.settings.value.pauseWhenNotWorn)
        assertFalse(withTimeout(5_000) { vm.state.first { !it.captureCue && !it.pauseWhenNotWorn } }.captureCue)
        vm.cancelScope()
    }

    @Test fun smartIsSentWhenChosenAndFixedWhenSwitched() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureMode = CaptureMode.SMART))
        val vm = CaptureSettingsViewModel(hud)
        vm.setMode(CaptureMode.FIXED); assertEquals(CaptureMode.FIXED, hud.settings.value.captureMode)
        vm.cancelScope()
    }

    @Test fun statusLineCountsKeyframesInTheLastTenMinutes() {
        assertEquals("Smart \u00b7 5 keyframes in the last 10 min", CaptureSettingsUiState(wake = CameraWake.MOTION, keyframesPerHour = 30).statusLine)
        assertEquals("Smart \u00b7 1 keyframe in the last 10 min", CaptureSettingsUiState(wake = CameraWake.MOTION, keyframesPerHour = 6).statusLine)
        show(CaptureSettingsUiState(mode = CaptureMode.SMART, wake = CameraWake.MOTION, keyframesPerHour = 30))
        compose.onNodeWithTag("capture_status").assertExists()
    }

    private fun pick(tag: String, index: Int) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("${tag}_$index").performClick(); compose.waitForIdle()
    }

    @Test fun modeHasThreeOptionsAndSettleWritesSmartSettle() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureMode = CaptureMode.FIXED))
        val vm = CaptureSettingsViewModel(hud)
        show(CaptureSettingsUiState(mode = CaptureMode.FIXED), vm)
        compose.onNodeWithTag("mode").performSemanticsAction(SemanticsActions.OnClick); compose.waitForIdle()
        compose.onNodeWithTag("mode_0").assertExists(); compose.onNodeWithTag("mode_1").assertExists(); compose.onNodeWithTag("mode_2").assertExists()
        compose.onNodeWithTag("mode_3").assertDoesNotExist()
        compose.onNodeWithText("Settle").assertExists()
        compose.onNodeWithTag("mode_1").performClick(); compose.waitForIdle()
        assertEquals(CaptureMode.SMART, hud.settings.value.captureMode); assertEquals(CameraWake.SETTLE, hud.settings.value.cameraWake)
        vm.cancelScope()
    }

    @Test fun fixedKeepsWakeAndSmartResetsToMotion() = runBlocking {
        val hud = FakeHudSettings(HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE))
        val vm = CaptureSettingsViewModel(hud)
        vm.setModeChoice(2)
        assertEquals(CaptureMode.FIXED, hud.settings.value.captureMode); assertEquals(CameraWake.SETTLE, hud.settings.value.cameraWake)
        vm.setModeChoice(0)
        assertEquals(CaptureMode.SMART, hud.settings.value.captureMode); assertEquals(CameraWake.MOTION, hud.settings.value.cameraWake)
        vm.cancelScope()
    }

    @Test fun settleShowsItsNoteAndHidesQuickLook() {
        show(CaptureSettingsUiState(mode = CaptureMode.SMART, wake = CameraWake.SETTLE))
        compose.onNodeWithTag("settle_note").assertExists()
        compose.onNodeWithText("Photos only when your head settles on something (about 1.5\u20132 s). Uses much less battery.").assertExists()
        compose.onNodeWithTag("quick_look").assertDoesNotExist()
        compose.onNodeWithContentDescription("Capture mode: Settle").assertExists()
    }

    @Test fun smartMotionShowsQuickLookNotSettleNote() {
        show(CaptureSettingsUiState(wake = CameraWake.MOTION))
        compose.onNodeWithTag("quick_look").assertExists(); compose.onNodeWithTag("settle_note").assertDoesNotExist()
    }

    @Test fun defaultStateShowsSettleNoteAndHidesQuickLook() {
        show(CaptureSettingsUiState())
        compose.onNodeWithTag("settle_note").assertExists(); compose.onNodeWithTag("quick_look").assertDoesNotExist()
    }

    @Test fun batteryTargetDefaultsToOffAndPickingFourWritesFour() = runBlocking {
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        show(CaptureSettingsUiState(), vm)
        compose.onNodeWithContentDescription("Battery target: Off").assertExists()
        compose.onNodeWithText("Takes fewer photos when the glasses battery would not last this long.").assertExists()
        pick("battery_target", 2)
        assertEquals(4, hud.settings.value.batteryTargetHours)
        vm.setBatteryTarget(5); assertEquals(5, hud.settings.value.batteryTargetHours)
        vm.setBatteryTarget(7); assertEquals(6, hud.settings.value.batteryTargetHours)
        vm.cancelScope()
    }

    @Test fun phoneUseSwitchDefaultsOffAndToggles() = runBlocking {
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        show(CaptureSettingsUiState(), vm)
        compose.onNodeWithTag("pause_phone_use").assertExists()
        compose.onNodeWithText("Pause while I use my phone").assertExists()
        assertFalse(hud.settings.value.pauseWhenPhoneInUse)
        compose.onNodeWithTag("pause_phone_use").performClick(); compose.waitForIdle()
        assertTrue(hud.settings.value.pauseWhenPhoneInUse)
        assertTrue(withTimeout(5_000) { vm.state.first { it.pauseWhenPhoneInUse } }.pauseWhenPhoneInUse)
        vm.cancelScope()
    }

    @Test fun statusLineReadsSettle() {
        assertEquals("Settle \u00b7 5 keyframes in the last 10 min", CaptureSettingsUiState(wake = CameraWake.SETTLE, keyframesPerHour = 30).statusLine)
        assertEquals("Fixed \u00b7 0 keyframes in the last 10 min", CaptureSettingsUiState(mode = CaptureMode.FIXED, wake = CameraWake.SETTLE).statusLine)
    }

    @Test fun workRestSwitchDefaultsOffAndGatesRows() = runBlocking {
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        show(CaptureSettingsUiState(), vm)
        compose.onNodeWithTag("work_rest").assertExists()
        compose.onNodeWithTag("work").assertDoesNotExist(); compose.onNodeWithTag("rest").assertDoesNotExist()
        compose.onNodeWithTag("work_rest").performClick(); compose.waitForIdle()
        assertTrue(hud.settings.value.workRestEnabled)
        vm.cancelScope()
    }

    @Test fun workRowShownWhenWorkRestOn() {
        show(CaptureSettingsUiState(workRestEnabled = true))
        compose.onNodeWithTag("work").assertExists()
    }

    @Test fun motionSourcesAndLabels() = runBlocking {
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        show(CaptureSettingsUiState(), vm)
        compose.onNodeWithTag("motion_sources").assertExists()
        compose.onNodeWithTag("source_glasses_imu").assertIsNotEnabled()
        compose.onNodeWithTag("source_phone_motion").assertDoesNotExist()
        compose.onNodeWithTag("source_watch_livefit").assertDoesNotExist()
        compose.onNodeWithTag("context_sources_link").assertExists()
        compose.onNodeWithTag("motion_label_note").assertDoesNotExist()
        pick("motion_label", 4)
        assertEquals(MotionLabel.EATING, hud.settings.value.motionLabel)
        vm.setMotionSource("phone_motion", false)
        assertEquals(listOf("phone_motion"), hud.settings.value.motionSourcesOff)
        vm.setMotionSource("phone_motion", true)
        assertEquals(emptyList<String>(), hud.settings.value.motionSourcesOff)
        vm.setMotionSource("glasses_imu", false); assertEquals(emptyList<String>(), hud.settings.value.motionSourcesOff)
        vm.cancelScope()
    }

    @Test fun contextSourcesLinkShowsStateWordAndOpens() {
        var opened = 0
        compose.setContent { PcTheme { CaptureSettingsScreen(CaptureSettingsUiState(contextWord = "Walking"), onBack = {}, onOpenContext = { opened++ }) } }
        compose.onNodeWithTag("context_sources_link").assertExists()
        compose.onNodeWithText("Phone, car, watch and photo context").assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Walking") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("context_sources_link"))).assertExists()
        compose.onNodeWithTag("context_sources_link").performClick(); compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun viewModelCarriesTheCommittedContextWord() = runBlocking {
        val st = kotlinx.coroutines.flow.MutableStateFlow(com.rokid.context.core.model.context.ContextState.VEHICLE_PASSENGER)
        val vm = CaptureSettingsViewModel(FakeHudSettings(), context = st)
        assertEquals("Passenger", withTimeout(5_000) { vm.state.first { it.contextWord == "Passenger" } }.contextWord)
        vm.cancelScope()
    }

    @Test fun motionLabelNoteShownWhenLabelSet() {
        show(CaptureSettingsUiState(motionLabel = MotionLabel.EATING))
        compose.onNodeWithTag("motion_label_note").assertExists()
    }

    // ---- Wear detection card ----

    @Test fun wearParkSettingsRoundTripThroughTheViewModelAndClamp() = runBlocking {
        val hud = FakeHudSettings()
        val vm = CaptureSettingsViewModel(hud)
        val s = withTimeout(5_000) { vm.state.first() }
        assertTrue(s.wearParkEnabled); assertEquals(120, s.wearParkUnknownSec); assertEquals(600, s.wearParkWornSec)
        vm.setWearParkEnabled(false); assertFalse(hud.settings.value.wearParkEnabled)
        vm.setWearParkUnknownSec(300); assertEquals(300, hud.settings.value.wearParkUnknownSec)
        vm.setWearParkWornSec(5); assertEquals(30, hud.settings.value.wearParkWornSec)
        vm.setWearParkWornSec(99_999); assertEquals(1_800, hud.settings.value.wearParkWornSec)
        vm.cancelScope()
    }

    @Test fun missingWarningNeedsMoreThanThreeMinutesWithoutASignal() {
        assertFalse(WearGuide.missingLong(null, 10_000_000L))
        assertFalse(WearGuide.missingLong(0L, WearGuide.MISSING_AFTER_MS))
        assertTrue(WearGuide.missingLong(0L, WearGuide.MISSING_AFTER_MS + 1))
    }

    @Test fun viewModelShowsSignalAvailableThenMissingLong() = runBlocking {
        val hud = FakeHudSettings()
        val since = kotlinx.coroutines.flow.MutableStateFlow<Long?>(0L)
        val vm = CaptureSettingsViewModel(hud, wearUnknownSince = since, clock = { 1_000_000L }, ticker = kotlinx.coroutines.flow.flowOf(Unit))
        assertTrue(withTimeout(5_000) { vm.state.first { it.wearMissingLong } }.wearMissingLong)
        since.value = null
        val s = withTimeout(5_000) { vm.state.first { !it.wearMissingLong } }
        assertTrue(s.wearSignalKnown)
        vm.cancelScope()
    }

    @Test fun wearCardShowsBatteryTextWithoutClaimingAReason() {
        show(CaptureSettingsUiState())
        compose.onNodeWithTag("wear_card").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turning it on in Hi Rokid may use more battery").assertExists()
        compose.onNodeWithTag("wear_steps").assertExists()
        compose.onNodeWithTag("wear_missing").assertDoesNotExist()
        compose.onNodeWithTag("open_hi_rokid").assertDoesNotExist()
    }

    @Test fun wearCardWarnsWhenMissingAndOffersOpenWhenInstalled() {
        var opened = 0
        compose.setContent { PcTheme { CaptureSettingsScreen(CaptureSettingsUiState(wearSignalKnown = false, wearMissingLong = true), onBack = {}, onOpenHiRokid = { opened++ }) } }
        compose.onNodeWithTag("wear_missing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("open_hi_rokid").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test fun wearCardSwitchAndPickersCallBack() {
        var enabled: Boolean? = null; var unknown = 0; var worn = 0
        compose.setContent { PcTheme { CaptureSettingsScreen(CaptureSettingsUiState(), onBack = {}, onWearParkEnabled = { enabled = it }, onWearParkUnknown = { unknown = it }, onWearParkWorn = { worn = it }) } }
        compose.onNodeWithTag("wear_park_enabled").performScrollTo().performClick()
        assertEquals(false, enabled)
        compose.onNodeWithTag("wear_park_unknown").performScrollTo().performClick()
        compose.onNodeWithText("5 min").performClick()
        assertEquals(300, unknown)
        assertEquals(0, worn)
    }

    @Test fun lagOptionsKeepACustomStoredValueAndStayInRange() {
        assertEquals(listOf(30, 60, 120, 300, 600, 900, 1_800), WearGuide.LAG_SEC)
        assertEquals((WearGuide.LAG_SEC + 45).sorted(), WearGuide.lagOptions(45))
    }

    // ---- Task 28: the capture plan row, "Frozen in canvas plan vN" (S12), Use settings (S26) ----

    private val plan = com.rokid.context.canvas.plan.ActivePlan(7, "g1", "Quiet walk", 3, "SHADOW", "abcdef123456", "EFFECTIVE")

    @Test fun noPlanShowsNoPlanRowAndNoFrozenLabels() {
        show(CaptureSettingsUiState(wake = CameraWake.MOTION, workRestEnabled = true))
        compose.onNodeWithTag("capture_plan_row").assertDoesNotExist()
        compose.onNodeWithTag("frozen_mode").assertDoesNotExist(); compose.onNodeWithTag("frozen_quick_look").assertDoesNotExist(); compose.onNodeWithTag("frozen_work_rest").assertDoesNotExist()
    }

    @Test fun theCapturePlanRowNamesThePlanAndUseSettingsTapsIt() {
        show(CaptureSettingsUiState(plan = plan))
        compose.onNodeWithText("Capture plan: Quiet walk v3 (shadow) \u00b7 Use settings").assertExists()
        compose.onNodeWithTag("capture_plan_row").performClick()
        assertEquals(1, used)
    }

    @Test fun theRowsThePlanResolvedAreLabelledFrozen() {
        show(CaptureSettingsUiState(plan = plan, wake = CameraWake.MOTION, workRestEnabled = true))
        for (tag in listOf("frozen_mode", "frozen_quick_look", "frozen_work_rest")) compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithTag("frozen_mode").assertTextEquals("Frozen in canvas plan v3")
    }

    @Test fun theFixedIntervalRowIsFrozenToo() {
        show(CaptureSettingsUiState(plan = plan, mode = CaptureMode.FIXED))
        compose.onNodeWithTag("frozen_interval").assertExists(); compose.onNodeWithTag("frozen_quick_look").assertDoesNotExist()
    }

    @Test fun theViewModelFollowsThePlanFlowAndUseSettingsCallsTheControl() = runBlocking {
        val active = kotlinx.coroutines.flow.MutableStateFlow<com.rokid.context.canvas.plan.ActivePlan?>(plan)
        var calls = 0
        val control = object : CapturePlanControl { override val active = active; override suspend fun useSettings() { calls++; active.value = null } }
        val vm = CaptureSettingsViewModel(FakeHudSettings(), plan = control)
        assertEquals(plan, withTimeout(5_000) { vm.state.first { it.plan != null } }.plan)
        vm.useSettings()
        assertEquals(null, withTimeout(5_000) { vm.state.first { it.plan == null } }.plan)
        assertEquals(1, calls)
        vm.cancelScope()
    }
}
