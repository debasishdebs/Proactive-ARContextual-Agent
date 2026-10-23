package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.canvas.FakeCanvasHost
import com.rokid.context.phone.canvas.ui.CanvasHomeScreen
import com.rokid.context.phone.canvas.ui.CanvasHomeUi
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.canvas.ui.PendingConfirmUi
import com.rokid.context.phone.ui.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** WP2 RED group (card side of the per-run confirmation): the card lists pending jobs with a countdown and a tap calls only the bound callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h3000dp")
class HelperConfirmCardTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private fun row(id: String, items: Int = 12, chars: Int = 340, expires: Long = 100_000L) = PendingConfirmRow(id, "frames", items, chars, expires)
    private val confirmed = mutableListOf<String>()
    private val declined = mutableListOf<String>()
    private val other = mutableListOf<String>()

    private fun show(pending: List<PendingConfirmUi>) = compose.setContent {
        PcTheme {
            CanvasHomeScreen(
                CanvasHomeUi(pending = pending), onBack = { other += "back" }, onOpenEditor = { other += "editor" }, onOpenBrowser = { other += "browser" },
                onLan = { other += "lan" }, onOpenPipeline = { other += "pipeline" }, onRollback = { other += "rollback" }, onSignOutAll = { other += "signout" },
                onMessageShown = {}, onConfirmRun = { confirmed += it }, onDeclineRun = { declined += it },
            )
        }
    }

    @Test fun noCardWithoutPendingJobs() {
        show(emptyList())
        compose.onNodeWithTag("confirm_card").assertDoesNotExist()
    }

    @Test fun cardListsEachPendingJobWithItsCountdown() {
        show(listOf(PendingConfirmUi(row("j1", items = 12, chars = 340), secondsLeft = 95), PendingConfirmUi(row("j2", items = 3, chars = 90), secondsLeft = 7)))
        compose.onNodeWithTag("confirm_card").assertExists()
        compose.onNodeWithTag("confirm_row_j1").assertTextEquals("Python on 12 frames · 340 characters")
        compose.onNodeWithTag("confirm_row_j2").assertTextEquals("Python on 3 frames · 90 characters")
        compose.onNodeWithTag("confirm_left_j1").assertTextEquals("Expires in 1:35")
        compose.onNodeWithTag("confirm_left_j2").assertTextEquals("Expires in 0:07")
    }

    @Test fun tapCallsOnlyTheBoundCallbackForThatJob() {
        show(listOf(PendingConfirmUi(row("j1"), 95), PendingConfirmUi(row("j2"), 50)))
        compose.onNodeWithTag("confirm_ok_j2").performClick()
        assertEquals(listOf("j2"), confirmed); assertTrue(declined.isEmpty())
        compose.onNodeWithTag("confirm_decline_j1").performClick()
        assertEquals(listOf("j2"), confirmed); assertEquals(listOf("j1"), declined)
        assertTrue("no other screen action ran: $other", other.isEmpty())
    }

    @Test fun cardHoldsOnlyHelperConfirmActionsAndTheTwoCallbacks() {
        val src = File("src/main/java/com/rokid/context/phone/canvas/ui/HelperCards.kt").readText()
        val sig = Regex("""fun HelperConfirmCard\((.*?)\)\s*\{""", RegexOption.DOT_MATCHES_ALL).find(src)!!.groupValues[1]
        val names = sig.split(',').map { it.trim().substringBefore(':').trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("pending", "onConfirm", "onCancel"), names)
        for (banned in listOf("HelperAuth", "HelperGrants", "HelperConfirm(", "HelperNative", "SecureRandom", "javax.crypto", "ConfirmBinding", "bundleHash")) {
            assertFalse("HelperCards.kt mentions $banned", src.contains(banned))
        }
    }

    // ---- view model plumbing (WP6 replaces the refresh, not these seams) -------------------------------------------------------------------------------

    @Test fun viewModelShowsPendingRowsWithCountdownAndDropsExpired() {
        val host = FakeCanvasHost()
        var now = 10_000L
        host.fakeConfirm.rows = listOf(row("j1", expires = 100_000L), row("j2", expires = 20_000L))
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { now })
        vm.tick()
        assertEquals(listOf("j1", "j2"), vm.state.value.pending.map { it.row.jobId })
        assertEquals(listOf(90, 10), vm.state.value.pending.map { it.secondsLeft })
        now = 20_000L
        vm.tick()
        assertEquals("expired entries drop at expiry", listOf("j1"), vm.state.value.pending.map { it.row.jobId })
        host.fakeConfirm.rows = emptyList()
        vm.tick()
        assertTrue(vm.state.value.pending.isEmpty())
    }

    @Test fun viewModelTapsReachOnlyTheConfirmActions() {
        val host = FakeCanvasHost()
        host.fakeConfirm.rows = listOf(row("j1"))
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.tick()
        host.calls.clear()
        vm.confirmRun("j1")
        vm.declineRun("j1")
        assertEquals(listOf("j1"), host.fakeConfirm.confirmed)
        assertEquals(listOf("j1"), host.fakeConfirm.cancelled)
        assertTrue("no other host call: ${host.calls}", host.calls.none { it != "refresh" })
        assertTrue(host.fakeNative.calls.isEmpty())
    }
}
