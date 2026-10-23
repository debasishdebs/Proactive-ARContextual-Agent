package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.canvas.CanvasCore
import com.rokid.context.phone.canvas.CanvasGraph
import com.rokid.context.phone.canvas.FakeHelperNative
import com.rokid.context.phone.canvas.HomeData
import com.rokid.context.phone.canvas.server.ApiHandler
import com.rokid.context.phone.canvas.server.AssetSource
import com.rokid.context.phone.canvas.server.CanvasAuth
import com.rokid.context.phone.canvas.server.HttpResponse
import com.rokid.context.phone.canvas.server.PkcsTlsIdentity
import com.rokid.context.phone.canvas.ui.CanvasHomeScreen
import com.rokid.context.phone.canvas.ui.CanvasHomeUi
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Controller note (review of the helper): the helper now REQUIRES at least 16 hex digits of the certificate fingerprint, so the native helper card DISPLAYS the first 16 hex
 * digits (64 bits), grouped 4 by 4, next to the pairing code and the host:port. The digits come from the certificate the server really presents (a real TLS handshake in
 * this test). The older canvas LAN pairing card keeps its 8-digit display (CanvasHomeTest).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w400dp-h3000dp")
class HelperPairingFingerprintTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private val core = object : CanvasCore {
        override val api = ApiHandler { HttpResponse.error(404, "not_found", "x") }
        override suspend fun start() {}
        override fun runActive() = false
        override suspend fun cancelActive() {}
        override suspend fun home() = HomeData(emptyList(), emptyList(), false)
        override suspend fun rollback(expectedId: Long): CanvasPublisher.Outcome = CanvasPublisher.Outcome.Blocked(emptyList())
        override suspend fun pipeline(id: String): Pipeline? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var graph: CanvasGraph? = null
    @After fun tearDown() { graph?.stop(); scope.cancel() }

    private fun presentedSha256Hex(port: Int): String {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        (ctx.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
            s.startHandshake()
            return MessageDigest.getInstance("SHA-256").digest(s.session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) }
        }
    }

    private fun newGraph(): CanvasGraph = CanvasGraph(
        CanvasAuth({ 1_000L }), core, AssetSource { null }, MutableStateFlow(true), wifiIp = { "192.168.1.20" }, scope = scope, clock = { 1_000L },
        tlsFactory = { PkcsTlsIdentity.load() }, helperNative = FakeHelperNative(),
    ).also { graph = it }

    @Test fun theCardShowsTheFirst16DigitsOfTheCertificateTheServerPresentsAndTheHostPort() {
        val g = newGraph()
        g.setLan(true)
        val port = g.status.value.port!!
        val presented = presentedSha256Hex(port)
        val vm = CanvasHomeViewModel(g, ensureService = {}, openUrl = {}, clock = { 1_000L }, io = main.dispatcher)
        vm.onStart()
        val card = vm.state.value.helper
        assertTrue(card.fingerprint16!!, Regex("^[0-9A-F]{4}( [0-9A-F]{4}){3}$").matches(card.fingerprint16!!))
        assertEquals("the first 16 hex digits of the presented certificate, 4 groups of 4", presented.take(16).uppercase().chunked(4).joinToString(" "), card.fingerprint16)
        assertEquals("192.168.1.20:$port", card.hostPort)
        vm.onStop()
    }

    @Test fun lanOffShowsNoFingerprintAndNoAddress() {
        val g = newGraph()
        g.start(false)
        val vm = CanvasHomeViewModel(g, ensureService = {}, openUrl = {}, clock = { 1_000L }, io = main.dispatcher)
        vm.onStart()
        assertNull(vm.state.value.helper.fingerprint16)
        assertNull(vm.state.value.helper.hostPort)
        vm.onStop()
    }

    @Test fun fingerprint16TakesExactlySixteenDigitsFromAnyGrouping() {
        assertEquals("0123 4567 89AB CDEF", HelperCardState.fingerprint16("0123 4567 89ab cdef 0123 4567 89AB CDEF"))
        assertEquals("0123 4567 89AB CDEF", HelperCardState.fingerprint16("01:23:45:67:89:ab:cd:ef:01:23"))
        assertNull(HelperCardState.fingerprint16("0123 4567 89AB"))
        assertNull(HelperCardState.fingerprint16(null))
    }

    private fun show(card: HelperCardState) = compose.setContent {
        PcTheme {
            CanvasHomeScreen(
                CanvasHomeUi(helper = card), onBack = {}, onOpenEditor = {}, onOpenBrowser = {}, onLan = {}, onOpenPipeline = {}, onRollback = {}, onSignOutAll = {}, onMessageShown = {},
            )
        }
    }

    @Test fun theCardDrawsTheCodeTheAddressAndSixteenDigitsTogether() {
        show(HelperCardState(available = true, pairingCode = "123456", pairingSecondsLeft = 90, fingerprint16 = "0123 4567 89AB CDEF", hostPort = "192.168.1.20:8765"))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasTestTag("helper_fingerprint"))
        compose.onNodeWithTag("helper_pair_code").assertTextEquals("123456")
        compose.onNodeWithTag("helper_host").assertTextEquals("192.168.1.20:8765")
        compose.onNodeWithTag("helper_fingerprint").assertTextEquals("0123 4567 89AB CDEF")
        compose.onNodeWithTag("helper_needs_lan").assertDoesNotExist()
    }

    @Test fun withoutLanTheCardAsksForItInsteadOfShowingDigits() {
        show(HelperCardState(available = true, pairingCode = "123456", pairingSecondsLeft = 90))
        compose.onNodeWithTag("canvas_scroll").performScrollToNode(hasTestTag("helper_needs_lan"))
        compose.onNodeWithTag("helper_fingerprint").assertDoesNotExist()
        compose.onNodeWithTag("helper_host").assertDoesNotExist()
    }
}
