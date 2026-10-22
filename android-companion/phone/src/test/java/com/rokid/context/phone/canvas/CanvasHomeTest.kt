package com.rokid.context.phone.canvas

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.canvas.server.PkcsTlsIdentity
import com.rokid.context.phone.canvas.ui.CanvasHomeScreen
import com.rokid.context.phone.canvas.ui.LAN_NOTE
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import com.rokid.context.phone.canvas.ui.CanvasHomeText
import com.rokid.context.phone.canvas.ui.CanvasHomeUi
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasHomeTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private fun show(ui: CanvasHomeUi, onNewCode: () -> Unit = {}, onNewCertificate: () -> Unit = {}) = compose.setContent {
        PcTheme {
            CanvasHomeScreen(
                ui, onBack = {}, onOpenEditor = {}, onOpenBrowser = {}, onLan = {}, onOpenPipeline = {}, onRollback = {}, onSignOutAll = {}, onMessageShown = {},
                onNewCode = onNewCode, onNewCertificate = onNewCertificate,
            )
        }
    }

    private val fp = "0123 4567 89AB CDEF 0123 4567 89AB CDEF FEDC BA98 7654 3210 FEDC BA98 7654 3210"
    private val lanStatus = CanvasStatus(on = true, port = 8765, lan = true, lanIp = "192.168.1.20", fingerprint = fp)

    @Test fun fingerprintShownWhenLanOn() {
        var rotated = 0
        show(CanvasHomeUi(status = lanStatus, wifiAvailable = true), onNewCertificate = { rotated++ })
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Certificate fingerprint"))
        compose.onNodeWithTag("canvas_lan_url").assertTextEquals("https://192.168.1.20:8765")
        compose.onNodeWithTag("canvas_fingerprint").assertTextEquals("01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF")
        compose.onNodeWithText("Ignore spaces, colons and capitals.").assertExists()
        compose.onNodeWithText("Your browser will warn once. Check that the fingerprint in its certificate details matches.").assertExists()
        compose.onNodeWithTag("canvas_fingerprint_toggle").performClick()
        compose.onNodeWithTag("canvas_fingerprint").assertTextEquals(fp.replace(" ", "").chunked(2).joinToString(":"))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasTestTag("canvas_new_certificate"))
        compose.onNodeWithTag("canvas_new_certificate").performClick()
        assertEquals(1, rotated)
    }

    @Test fun fingerprintHiddenWhenLanOff() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765, lanIp = "192.168.1.20", fingerprint = fp), wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
        compose.onNodeWithTag("canvas_fingerprint").assertDoesNotExist()
        compose.onNodeWithTag("canvas_new_certificate").assertDoesNotExist()
    }

    @Test fun lanDisabledWhenTlsUnavailable() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765, lanIp = "192.168.1.20", lanAvailable = false), wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
        compose.onNodeWithTag("canvas_lan_switch").assertIsNotEnabled()
        compose.onNodeWithText("Secure LAN isn't available on this phone").assertExists()
    }

    @Test fun lockedShowsNewCodeButton() {
        var taps = 0
        show(CanvasHomeUi(status = lanStatus.copy(pairLocked = true, code = null), showCode = false, wifiAvailable = true), onNewCode = { taps++ })
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Locked \u2014 tap New code"))
        compose.onNodeWithText("Locked \u2014 tap New code").assertIsDisplayed()
        compose.onNodeWithTag("canvas_code").assertDoesNotExist()
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasTestTag("canvas_new_code"))
        compose.onNodeWithTag("canvas_new_code").performClick()
        assertEquals(1, taps)
    }

    @Test fun wrongAttemptsFromAnotherDeviceAreShown() {
        show(CanvasHomeUi(status = lanStatus.copy(code = "123456", codeExpiresAt = 1, wrongAttempts = 7), showCode = true, wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("7 wrong attempts from another device"))
        compose.onNodeWithText("7 wrong attempts from another device").assertExists()
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
    }

    @Test fun noWrongAttemptLineWhenThereAreNone() {
        show(CanvasHomeUi(status = lanStatus.copy(code = "123456", codeExpiresAt = 1), showCode = true, wifiAvailable = true))
        compose.onAllNodes(hasText("wrong attempt", substring = true)).assertCountEquals(0)
    }

    @Test fun oneWrongAttemptIsSingular() {
        show(CanvasHomeUi(status = lanStatus.copy(code = "123456", codeExpiresAt = 1, wrongAttempts = 1), showCode = true, wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("1 wrong attempt from another device"))
    }

    @Test fun newCodeButtonAbsentWhenNotLocked() {
        show(CanvasHomeUi(status = lanStatus.copy(code = "123456", codeExpiresAt = 1), showCode = true, secondsLeft = 10, wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Pairing code"))
        compose.onNodeWithTag("canvas_new_code").assertDoesNotExist()
    }

    @Test fun noUnencryptedWarning() {
        show(CanvasHomeUi(status = lanStatus.copy(code = "123456", codeExpiresAt = 1), showCode = true, wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
        compose.onAllNodes(hasText("unencrypted", substring = true, ignoreCase = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("travel", substring = true, ignoreCase = true)).assertCountEquals(0)
        assertFalse(LAN_NOTE.contains("unencrypted", ignoreCase = true))
    }

    @Test fun tickNeverMintsAfterALockout() {
        val host = FakeCanvasHost()
        host.status.value = lanStatus.copy(code = null, pairLocked = true)
        host.lockedOut = true
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 5L })
        repeat(5) { vm.tick() }
        assertFalse("tick minted: ${host.calls}", host.calls.any { it == "code" || it == "newCode" })
        assertNull(host.status.value.code)
        vm.newCode()
        assertEquals(listOf("newCode"), host.calls.filter { it.startsWith("code") || it == "newCode" })
        assertNotNull(host.status.value.code)
        assertTrue(vm.state.value.showCode)
    }

    @Test fun tickStillMintsWhenNotLocked() {
        val host = FakeCanvasHost()
        host.status.value = lanStatus.copy(code = null)
        CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 5L }).tick()
        assertTrue(host.calls.contains("code"))
    }

    @Test fun newCertificateRotatesAndSaysSo() {
        val host = FakeCanvasHost()
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.newCertificate()
        assertEquals(listOf("rotate"), host.calls)
        assertEquals("New certificate. Every browser was signed out.", vm.state.value.message)
    }

    @Test fun openInBrowserFollowsTheSchemeAndRefusesWhileLocked() {
        val host = FakeCanvasHost()
        val opened = mutableListOf<String>()
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = { opened += it }, clock = { 0L })
        host.start(true)
        vm.openInBrowser()
        assertEquals(listOf("https://127.0.0.1:8765/#pair=654321"), opened)
        opened.clear(); host.lockedOut = true
        vm.openInBrowser()
        assertTrue(opened.isEmpty())
        assertEquals("Pairing is locked. Tap New code first.", vm.state.value.message)
    }

    @Test fun statusLineShowsUrl() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8767, lanIp = "192.168.1.20")))
        compose.onNodeWithTag("canvas_status").assertTextEquals("On \u00b7 127.0.0.1:8767")
    }

    @Test fun statusLineOff() {
        show(CanvasHomeUi())
        compose.onNodeWithTag("canvas_status").assertTextEquals("Off")
    }

    @Test fun lanSwitchShowsWarningAndCode() {
        show(CanvasHomeUi(
            status = CanvasStatus(on = true, port = 8765, lan = true, lanIp = "192.168.1.20", code = "123456", codeExpiresAt = 1), showCode = true, secondsLeft = 270,
        ))
        compose.onNodeWithTag("canvas_status").assertTextEquals("On \u00b7 LAN 192.168.1.20:8765 \u00b7 turns off after 30 min idle")
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText(LAN_NOTE))
        compose.onNodeWithText(LAN_NOTE).assertIsDisplayed()
        compose.onNodeWithTag("canvas_code").assertTextEquals("123456")
        compose.onNodeWithTag("canvas_code_left").assertTextEquals("Expires in 4:30")
    }

    @Test fun codeHiddenWhenNotShown() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765, code = "123456"), showCode = false))
        compose.onNodeWithTag("canvas_code").assertDoesNotExist()
    }

    @Test fun lanDisabledWithoutWifi() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765), wifiAvailable = false))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
        compose.onNodeWithTag("canvas_lan_switch").assertIsNotEnabled()
    }

    @Test fun lanEnabledWithWifi() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765), wifiAvailable = true))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Use from a laptop"))
        compose.onNodeWithTag("canvas_lan_switch").assertIsEnabled()
    }

    @Test fun rollbackBlockedShowsReason() {
        val host = FakeCanvasHost()
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.reload()
        vm.rollback()
        val msg = vm.state.value.message
        assertNotNull(msg)
        assertTrue(msg!!.contains("Critic was changed after the publish."))
        show(vm.state.value)
        compose.onNodeWithText(msg, substring = true).assertIsDisplayed()
    }

    @Test fun rollbackPublishedSaysSo() {
        val host = FakeCanvasHost().also { it.outcome = CanvasPublisher.Outcome.Published(1, emptyList()) }
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.reload()
        vm.rollback()
        assertEquals("Rolled back", vm.state.value.message)
    }

    @Test fun openInBrowserUsesBoundPortAndPairingCode() {
        val host = FakeCanvasHost()
        val opened = mutableListOf<String>()
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = { opened += it }, clock = { 0L })
        vm.openInBrowser()
        assertEquals(listOf("http://127.0.0.1:8765/#pair=654321"), opened)
        assertTrue(vm.state.value.showCode)
    }

    @Test fun signOutAllRevokes() {
        val host = FakeCanvasHost()
        CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L }).signOutAll()
        assertEquals(listOf("revoke"), host.calls)
    }

    private var cancelled = 0

    @Test fun devModeOffStopsServer() {
        val auth = CanvasAuth({ 1_000L })
        val dev = MutableStateFlow(true)
        val core = object : CanvasCore {
            override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
            override suspend fun start() {}
            override fun runActive() = false
            override suspend fun cancelActive() { cancelled++ }
            override suspend fun home() = HomeData(emptyList(), emptyList(), false)
            override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
            override suspend fun pipeline(id: String): Pipeline? = null
        }
        val graph = CanvasGraph(auth, core, AssetSource { null }, dev, wifiIp = { "192.168.1.20" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1_000L })
        try {
            graph.start(false)
            val port = graph.status.value.port
            assertTrue(graph.status.value.on)
            assertNotNull(port)
            auth.newWebViewToken()
            assertEquals(1, auth.sessionCount())
            dev.value = false
            assertFalse(graph.status.value.on)
            assertNull(graph.status.value.port)
            assertEquals(0, auth.sessionCount())
            assertEquals("an active run is cancelled", 1, cancelled)
            graph.start(false) // dev mode off: the canvas never runs
            assertFalse(graph.status.value.on)
        } finally { graph.stop() }
    }

    @Test fun lanOffByDefaultAndPortMatchesBound() {
        val auth = CanvasAuth({ 1_000L })
        val core = object : CanvasCore {
            override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
            override suspend fun start() {}
            override fun runActive() = false
            override suspend fun cancelActive() { cancelled++ }
            override suspend fun home() = HomeData(emptyList(), emptyList(), false)
            override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
            override suspend fun pipeline(id: String): Pipeline? = null
        }
        val graph = CanvasGraph(auth, core, AssetSource { null }, MutableStateFlow(true), wifiIp = { "192.168.1.20" }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1_000L }, tlsFactory = { PkcsTlsIdentity.load() })
        try {
            graph.start(false)
            assertFalse(graph.status.value.lan)
            val p = graph.status.value.port!!
            val c = java.net.URL("http://127.0.0.1:$p/").openConnection() as java.net.HttpURLConnection
            assertEquals(404, c.responseCode) // allowed host (127.0.0.1:<bound port>); no asset
            graph.setLan(true)
            assertTrue(graph.status.value.lan)
            assertNotNull(graph.status.value.code)
            assertNotNull(graph.status.value.fingerprint)
            assertTrue(graph.status.value.lanUrl!!.startsWith("https://"))
            graph.setLan(false)
            assertFalse(graph.status.value.lan)
        } finally { graph.stop() }
    }

    @Test fun rollbackNamesTheNewestPublishItSaw() {
        val host = FakeCanvasHost().also { it.newest = PublishRow(42, "interpreter", "SHADOW"); it.outcome = CanvasPublisher.Outcome.Published(42, emptyList()) }
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.reload()
        assertEquals(PublishRow(42, "interpreter", "SHADOW"), vm.state.value.newestPublish)
        vm.rollback()
        assertEquals(listOf(42L), host.rollbackIds)
    }

    @Test fun rollbackWithNothingShownDoesNothing() {
        val host = FakeCanvasHost().also { it.newest = null }
        val vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { 0L })
        vm.reload()
        vm.rollback()
        assertEquals(emptyList<Long>(), host.rollbackIds)
    }

    @Test fun rollbackButtonShowsForAnInterpreterPublishToo() {
        show(CanvasHomeUi(status = CanvasStatus(on = true, port = 8765), publishActive = true, newestPublish = PublishRow(3, "interpreter", "LIVE")))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasText("Roll back live pipeline"))
        compose.onNodeWithTag("canvas_rollback").assertIsDisplayed()
        assertEquals("Roll back Shadow pipeline", CanvasHomeText.rollbackLabel(PublishRow(3, "interpreter", "SHADOW")))
        assertEquals("Roll back live", CanvasHomeText.rollbackLabel(PublishRow(3, "prompts", null)))
    }
}
