package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.context.ContextHubStats
import com.rokid.context.phone.context.ContextSourceSettings
import com.rokid.context.phone.context.SourceStatus
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2400dp")
class ContextSourcesSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    private companion object { val n = AtomicInteger() }
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ContextSourceSettings(ctx.getSharedPreferences("css_${n.incrementAndGet()}", 0).also { it.edit().clear().commit() })
    private val decision = MutableStateFlow(ContextDecision(ContextState.UNKNOWN, 0f, 0L, emptySet(), null, ContextState.UNKNOWN, false))
    private val status = MutableStateFlow(mapOf("watch_livefit" to SourceStatus.OFF))
    private val stats = ContextHubStats { 0L }
    private val digest = "ab".repeat(32)

    private val grantedPerms = mutableSetOf<String>()
    private var bonded = listOf("AA:BB:CC:00:00:01" to "Civic", "AA:BB:CC:00:00:02" to "Van")
    private var btOn = true
    private var settingsOpened = 0
    private val asked = mutableListOf<List<String>>()
    private val bt = "android.permission.BLUETOOTH_CONNECT"
    private val activityPerm = "android.permission.ACTIVITY_RECOGNITION"
    private val finePerm = "android.permission.ACCESS_FINE_LOCATION"
    private val coarsePerm = "android.permission.ACCESS_COARSE_LOCATION"

    private fun vm(cert: String? = digest) = ContextSourcesViewModel(settings, LiveFitCertLookup { cert }, decision, status, stats, granted = { it in grantedPerms }, bonded = { bonded }, bluetoothOn = { btOn })

    private fun show(vm: ContextSourcesViewModel) {
        compose.setContent {
            PcTheme {
                val s = vm.state.collectAsState().value
                ContextSourcesScreen(s, onBack = {}, onApply = vm::setApply, onSource = { id, on -> vm.setSource(id, on) { asked += it.toList() } }, onToggleCar = vm::setMyCar, onRefreshCars = vm::refreshCars, onOpenAppSettings = { settingsOpened++ })
            }
        }
        compose.waitForIdle()
    }

    @Test fun bothSwitchesRenderWatchOffApplyOn() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_apply").assertIsOn()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        compose.onNodeWithTag("ctx_watch_missing").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun liveFitMissingShowsNoteAndStaysOff() {
        val v = vm(cert = null); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_missing").assertTextContains("Install LiveFit on this phone first.")
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        assertFalse(settings.enabled("watch_livefit")); assertNull(settings.liveFitCertSha256)
        v.cancelScope()
    }

    @Test fun liveFitPresentPinsDigestAndTurnsOn() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOn()
        assertTrue(settings.enabled("watch_livefit")); assertEquals(digest, settings.liveFitCertSha256)
        compose.onNodeWithTag("ctx_watch_missing").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun turningWatchOffClearsDigest() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        assertFalse(settings.enabled("watch_livefit")); assertNull(settings.liveFitCertSha256)
        v.cancelScope()
    }

    @Test fun applySwitchWritesTheSetting() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_apply").performClick(); compose.waitForIdle()
        assertFalse(settings.applyToCapture); compose.onNodeWithTag("ctx_apply").assertIsOff()
        compose.onNodeWithTag("ctx_apply").performClick(); compose.waitForIdle()
        assertTrue(settings.applyToCapture)
        v.cancelScope()
    }

    @Test fun stateLineShowsDecisionWatchStatusAndCounts() {
        decision.value = decision.value.copy(state = ContextState.VEHICLE_DRIVING)
        status.value = mapOf("watch_livefit" to SourceStatus.FAILED)
        stats.onWatchRejected(); stats.onWatchRejected()
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_state").assertTextContains("Driving", substring = true)
            .assertTextContains("FAILED", substring = true).assertTextContains("rejected 2", substring = true).assertTextContains("watch msgs 0", substring = true)
        v.cancelScope()
    }

    @Test fun multiSignerLiveFitCannotBePinned() {
        val lookup = object : LiveFitCertLookup { override fun sha256(): String? = null; override fun cannotPin() = true }
        val v = ContextSourcesViewModel(settings, lookup, decision, status, stats); show(v)
        compose.onNodeWithTag("ctx_watch_livefit").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_watch_missing").assertTextContains("more than one signing certificate", substring = true)
        compose.onNodeWithTag("ctx_watch_livefit").assertIsOff()
        assertFalse(settings.enabled("watch_livefit")); assertNull(settings.liveFitCertSha256)
        v.cancelScope()
    }

    @Test fun rejectedWithNoAcceptedShowsReToggleHint() {
        settings.setEnabled("watch_livefit", true); settings.liveFitCertSha256 = digest
        stats.onWatchRejected()
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_hint").assertTextContains("off and on", substring = true)
        v.cancelScope()
    }

    @Test fun noHintWhenWatchOffOrNothingRejected() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_watch_hint").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun stateWordsCoverEveryState() {
        assertEquals(listOf("Unknown", "Resting", "Walking", "Passenger", "Driving", "Flying"), ContextState.entries.map { ContextSourcesUiState.word(it) })
    }

    @Test fun viewModelStateFollowsSettingsChangesMadeElsewhere() = runBlocking {
        val v = vm()
        assertTrue(withTimeout(5_000) { v.state.first() }.applyToCapture)
        settings.applyToCapture = false
        assertFalse(withTimeout(5_000) { v.state.first { !it.applyToCapture } }.applyToCapture)
        v.cancelScope()
    }

    private val phoneIds = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "image_intent")

    @Test fun everySwitchIsOffByDefault() {
        val v = vm(); show(v)
        for (id in phoneIds + "watch_livefit") { compose.onNodeWithTag("ctx_$id").assertIsOff(); assertFalse(settings.enabled(id)) }
        compose.onNodeWithTag("ctx_my_cars").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun carAsksForBluetoothConnectAndGrantedEnables() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        assertEquals(listOf(listOf(bt)), asked)
        assertFalse(settings.enabled("phone_car")) // nothing starts before the answer
        grantedPerms += bt; v.onPermissionResult(mapOf(bt to true)); compose.waitForIdle()
        assertTrue(settings.enabled("phone_car")); compose.onNodeWithTag("ctx_phone_car").assertIsOn()
        compose.onNodeWithTag("ctx_denied_phone_car").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun carDeniedStaysOffWithReason() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        v.onPermissionResult(mapOf(bt to false)); compose.waitForIdle()
        assertFalse(settings.enabled("phone_car")); compose.onNodeWithTag("ctx_phone_car").assertIsOff()
        compose.onNodeWithTag("ctx_denied_phone_car").assertTextContains("Permission needed", substring = true).assertTextContains("Turn it on again to ask", substring = true)
        // asking again clears the note
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_denied_phone_car").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun alreadyGrantedEnablesWithoutAsking() {
        grantedPerms += activityPerm
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_activity").performClick(); compose.waitForIdle()
        assertTrue(asked.isEmpty()); assertTrue(settings.enabled("phone_activity"))
        v.cancelScope()
    }

    @Test fun activityAndStepsAskForActivityRecognition() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_steps").performClick(); compose.waitForIdle()
        assertEquals(listOf(listOf(activityPerm)), asked)
        v.onPermissionResult(mapOf(activityPerm to true)); compose.waitForIdle()
        assertTrue(settings.enabled("phone_steps"))
        v.cancelScope()
    }

    @Test fun speedAsksForBothLocationPermissionsAndCoarseOnlyIsAcceptedWithANote() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_speed").performClick(); compose.waitForIdle()
        assertEquals(listOf(listOf(finePerm, coarsePerm)), asked)
        grantedPerms += coarsePerm; v.onPermissionResult(mapOf(finePerm to false, coarsePerm to true)); compose.waitForIdle()
        assertTrue(settings.enabled("phone_speed"))
        compose.onNodeWithTag("ctx_speed_note").assertTextContains("Approximate location: only long fast trips are detected")
        assertTrue(asked.flatten().none { it.contains("BACKGROUND") })
        v.cancelScope()
    }

    @Test fun speedWithFineHasNoApproximateNote() {
        grantedPerms += finePerm; grantedPerms += coarsePerm
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_speed").performClick(); compose.waitForIdle()
        assertTrue(settings.enabled("phone_speed")); compose.onNodeWithTag("ctx_speed_note").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun imageIntentNeedsNoPermissionAndExplainsPhotosGoOnlyInsideAVehicleEpisode() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_image_intent_note").assertTextContains("vehicle episode", substring = true).assertTextContains("only while", substring = true)
        compose.onNodeWithTag("ctx_image_intent").performClick(); compose.waitForIdle()
        assertTrue(asked.isEmpty()); assertTrue(settings.enabled("image_intent")); compose.onNodeWithTag("ctx_image_intent").assertIsOn()
        v.cancelScope()
    }

    @Test fun turningASwitchOffDisablesIt() {
        grantedPerms += bt
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        assertFalse(settings.enabled("phone_car")); compose.onNodeWithTag("ctx_phone_car").assertIsOff()
        v.cancelScope()
    }

    @Test fun unavailableSourceShowsNotAvailableOnThisPhone() {
        settings.setEnabled("phone_steps", true)
        status.value = mapOf("phone_steps" to SourceStatus.UNAVAILABLE)
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_unavailable_phone_steps").assertTextContains("Not available on this phone")
        compose.onNodeWithTag("ctx_unavailable_phone_car").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun failedSourceSaysWhatToDo() {
        settings.setEnabled("phone_car", true)
        status.value = mapOf("phone_car" to SourceStatus.FAILED)
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_failed_phone_car").assertTextContains("turn it off and on", substring = true)
        v.cancelScope()
    }

    @Test fun myCarsHiddenWhileCarOffShownWhenOnWithNamesOnly() {
        grantedPerms += bt
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_my_cars").assertDoesNotExist()
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_my_cars").assertIsDisplayed()
        compose.onNodeWithTag("ctx_car_0").assertTextContains("Civic", substring = true).assertIsOff()
        compose.onNodeWithTag("ctx_car_1").assertTextContains("Van", substring = true)
        v.cancelScope()
    }

    @Test fun pickingACarStoresItsAddressOnlyInTheHubStateAndUnpickingRemovesIt() {
        grantedPerms += bt
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_car_1").performClick(); compose.waitForIdle()
        assertEquals(setOf("AA:BB:CC:00:00:02"), settings.myCars)
        compose.onNodeWithTag("ctx_car_1").assertIsOn()
        compose.onNodeWithTag("ctx_car_1").performClick(); compose.waitForIdle()
        assertTrue(settings.myCars.isEmpty())
        v.cancelScope()
    }

    @Test fun noPairedDevicesSaysSo() {
        grantedPerms += bt; bonded = emptyList()
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_my_cars_empty").assertTextContains("No paired Bluetooth devices", substring = true)
        v.cancelScope()
    }

    @Test fun carRowToStringHidesTheAddress() {
        assertFalse(CarRow("AA:BB:CC:00:00:01", "Civic", false).toString().contains("AA:BB"))
        assertFalse(CarRow("AA:BB:CC:00:00:01", "Civic", false).toString().contains("Civic"))
    }

    @Test fun unnamedDeviceDoesNotShowItsAddress() {
        grantedPerms += bt; bonded = listOf("AA:BB:CC:00:00:09" to "AA:BB:CC:00:00:09")
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_car_0").assertTextContains("Unnamed device", substring = true)
        v.cancelScope()
    }

    @Test fun switchRowsHaveATouchTargetOfAtLeast48dp() {
        val v = vm(); show(v)
        for (id in phoneIds) compose.onNodeWithTag("ctx_$id").assertHeightIsAtLeast(48.dp)
        v.cancelScope()
    }

    @Test fun clearPersonalClearsMyCarsAndTheSwitchesFollow() {
        grantedPerms += bt
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("ctx_car_0").performClick(); compose.waitForIdle()
        settings.setEnabled("image_intent", true)
        settings.clearPersonal(); compose.waitForIdle()
        assertTrue(settings.myCars.isEmpty()); assertFalse(settings.enabled("image_intent"))
        compose.onNodeWithTag("ctx_image_intent").assertIsOff(); compose.onNodeWithTag("ctx_car_0").assertIsOff()
        v.cancelScope()
    }

    @Test fun carOnWithoutBluetoothPermissionExplainsItselfAndGrantedDoesNot() {
        settings.setEnabled("phone_car", true)
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_perm_missing_phone_car").assertTextContains("Bluetooth permission is missing", substring = true)
        grantedPerms += bt
        settings.applyToCapture = false // a settings change makes the state re-read
        compose.waitForIdle()
        compose.onNodeWithTag("ctx_perm_missing_phone_car").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun speedSubtitleSaysLocationRunsInThePocketAndBackgroundPermissionIsNeverAsked() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_speed").assertTextContains("with the phone in your pocket", substring = true)
            .assertTextContains("background-location permission is never requested", substring = true)
        compose.onNodeWithTag("ctx_phone_speed").assertTextDoesNotContain("only while the app is open")
        v.cancelScope()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextDoesNotContain(t: String) {
        val texts = fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertFalse(texts, texts.contains(t))
    }

    @Test fun deleteAllPathClearsMyCarsEvenWhenTheWipeThrows() = runBlocking {
        settings.addMyCar("AA:BB:CC:00:00:01"); settings.setEnabled("image_intent", true)
        val r = runCatching { settings.wipeThenClear { error("disk") } }
        assertTrue(r.isFailure); assertTrue(settings.myCars.isEmpty()); assertFalse(settings.enabled("image_intent"))
    }

    @Test fun imageIntentOnWithNoCarListedSaysNothingIsSent() {
        settings.setEnabled("image_intent", true)
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_image_intent_nocar").assertTextContains("No car listed yet", substring = true)
        settings.setEnabled("phone_car", true); settings.addMyCar("AA:BB:CC:00:00:01"); compose.waitForIdle()
        compose.onNodeWithTag("ctx_image_intent_nocar").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun noCarNoteHiddenWhileImageIntentIsOff() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_image_intent_nocar").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun carOnWithPermissionButBluetoothOffSaysSo() {
        grantedPerms += bt; btOn = false
        settings.setEnabled("phone_car", true)
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_bt_off_phone_car").assertTextContains("Bluetooth is off", substring = true)
        compose.onNodeWithTag("ctx_perm_missing_phone_car").assertDoesNotExist()
        v.cancelScope()
    }

    @Test fun deniedNoteOffersOpenAppSettings() {
        val v = vm(); show(v)
        compose.onNodeWithTag("ctx_phone_car").performClick(); compose.waitForIdle()
        v.onPermissionResult(mapOf(bt to false)); compose.waitForIdle()
        compose.onNodeWithTag("ctx_open_settings_phone_car").performClick()
        assertEquals(1, settingsOpened)
        v.cancelScope()
    }
}
