package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.canvas.FakeCanvasHost
import com.rokid.context.phone.canvas.ui.CanvasHomeScreen
import com.rokid.context.phone.canvas.ui.CanvasHomeUi
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WP2 RED group: the native helper card (accept pairing, py_exec grant, disconnect) on the canvas home screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h3000dp")
class HelperCardTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private val calls = mutableListOf<String>()
    private fun show(card: HelperCardState) = compose.setContent {
        PcTheme {
            CanvasHomeScreen(
                CanvasHomeUi(helper = card), onBack = {}, onOpenEditor = {}, onOpenBrowser = {}, onLan = {}, onOpenPipeline = {}, onRollback = {}, onSignOutAll = {}, onMessageShown = {},
                onAcceptPairing = { calls += "accept" }, onGrantPyExec = { calls += "grant:$it" }, onDisconnectHelper = { calls += "disconnect" },
            )
        }
    }
    private fun reveal(tag: String) = compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasTestTag(tag))

    @Test fun unavailableHostShowsNoCard() {
        show(HelperCardState.UNAVAILABLE)
        compose.onNodeWithTag("helper_card").assertDoesNotExist()
    }

    @Test fun unpairedCardOffersAcceptPairing() {
        show(HelperCardState(available = true))
        reveal("helper_accept_pairing")
        compose.onNodeWithTag("helper_accept_pairing").performClick()
        assertEquals(listOf("accept"), calls)
    }

    @Test fun openWindowShowsTheCodeAndItsCountdown() {
        show(HelperCardState(available = true, pairingCode = "482913", pairingSecondsLeft = 75))
        reveal("helper_pair_code")
        compose.onNodeWithTag("helper_pair_code").assertTextEquals("482913")
        compose.onNodeWithTag("helper_pair_left").assertTextEquals("Expires in 1:15")
    }

    @Test fun pairedCardShowsTheNameTheGrantSwitchAndDisconnect() {
        show(HelperCardState(available = true, paired = true, helperName = "my-laptop"))
        reveal("helper_name")
        compose.onNodeWithTag("helper_name").assertTextEquals("my-laptop")
        reveal("helper_grant_switch")
        compose.onNodeWithTag("helper_grant_switch").performClick()
        reveal("helper_disconnect")
        compose.onNodeWithTag("helper_disconnect").performClick()
        assertEquals(listOf("grant:true", "disconnect"), calls)
    }

    @Test fun grantSwitchOffCallsGrantFalse() {
        show(HelperCardState(available = true, paired = true, helperName = "h", grantPyExec = true))
        reveal("helper_grant_switch")
        compose.onNodeWithTag("helper_grant_switch").performClick()
        assertEquals(listOf("grant:false"), calls)
    }

    @Test fun viewModelCallsOnlyTheNativeActions() {
        val host = FakeCanvasHost()
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L }, io = main.dispatcher)
        vm.onStart()                                                       // WP6: the card is read by the STARTED-scoped subscription, off the main thread
        assertTrue(vm.state.value.helper.available)
        host.calls.clear()
        vm.acceptPairing(); vm.setPyExecGrant(true); vm.disconnectHelper()
        assertEquals(listOf("accept", "grant:py_exec:true", "disconnect"), host.fakeNative.calls)
        assertTrue("the grant is on in the card state after the tap", vm.state.value.helper.grantPyExec)
        assertTrue("no other host call: ${host.calls}", host.calls.none { it != "refresh" })
    }
}
