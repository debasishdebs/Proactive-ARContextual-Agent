package com.rokid.context.phone.canvas

import android.app.Application
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.canvas.server.PkcsTlsIdentity
import com.rokid.context.phone.canvas.ui.CanvasWebClient
import com.rokid.context.phone.canvas.ui.CanvasWebKey
import com.rokid.context.phone.canvas.ui.SslResettableLoader
import com.rokid.context.phone.canvas.ui.applyWebKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSslErrorHandler
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasWebTlsTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()
    private val ours = PkcsTlsIdentity.load()
    private val theirs = PkcsTlsIdentity.other()

    private fun handler(): SslErrorHandler =
        SslErrorHandler::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    private fun callback(secure: Boolean, tls: PkcsTlsIdentity?, cert: java.security.cert.X509Certificate): ShadowSslErrorHandler {
        val h = handler()
        val client = CanvasWebClient(ctx, { 8765 }, { secure }, { tls })
        client.onReceivedSslError(WebView(ctx), h, SslError(SslError.SSL_UNTRUSTED, cert, "https://127.0.0.1:8765/"))
        return Shadow.extract(h)
    }

    @Test fun sslCallbackProceedsOnlyForOurCert() {
        val ok = callback(true, ours, ours.certificate)
        assertTrue(ok.wasProceedCalled()); assertFalse(ok.wasCancelCalled())
        val bad = callback(true, ours, theirs.certificate)
        assertFalse(bad.wasProceedCalled()); assertTrue(bad.wasCancelCalled())
    }

    @Test fun sslCallbackCancelsWithoutAnIdentityOrOnPlainHttp() {
        val none = callback(true, null, ours.certificate)
        assertFalse(none.wasProceedCalled()); assertTrue(none.wasCancelCalled())
        val plain = callback(false, ours, ours.certificate) // no TLS error can happen on http; if one is reported anyway, never proceed
        assertFalse(plain.wasProceedCalled()); assertTrue(plain.wasCancelCalled())
    }

    @Test fun sslErrorHasExactlyOnePathToProceed() {
        val src = File("src/main/java/com/rokid/context/phone/canvas/ui/CanvasWeb.kt").readText()
        assertEquals(1, Regex("\\.proceed\\(\\)").findAll(src).count())
        assertTrue(src.contains("SslDecision.decide"))
        assertFalse(src.contains("onReceivedClientCertRequest"))
        assertFalse(src.contains("onReceivedHttpAuthRequest"))
    }

    // ---- lifecycle key (S37) ----

    private class Loader : SslResettableLoader {
        val calls = mutableListOf<String>()
        override fun clearSslPreferences() { calls += "clear" }
        override fun loadUrl(url: String) { calls += "load:" + url.substringBefore("#") }
    }

    private var minted = 0
    private fun mint(): String = "tok${++minted}"

    @Test fun reloadsOnOriginChange() {
        val a = CanvasWebKey.next(null, "http", 8765, null, ::mint)
        assertTrue(CanvasWebKey.needsReload(null, a))
        // nothing changed: the same key, the same token, no reload and no new session
        val same = CanvasWebKey.next(a, "http", 8765, null, ::mint)
        assertEquals(a, same); assertFalse(CanvasWebKey.needsReload(a, same)); assertEquals(1, minted)
        // each part of the key forces a reload with a fresh token
        for (n in listOf(
            CanvasWebKey.next(a, "https", 8765, "AAAA", ::mint),
            CanvasWebKey.next(a, "http", 8766, null, ::mint),
            CanvasWebKey.next(a, "http", 8765, "BBBB", ::mint),
        )) {
            assertTrue(CanvasWebKey.needsReload(a, n))
            assertTrue(n.token != a.token)
        }
        assertTrue(CanvasWebKey.needsReload(CanvasWebKey("https", 1, "A", "t"), CanvasWebKey("https", 1, "A", "u")))
    }

    @Test fun urlCarriesTheTokenInTheFragmentOnly() {
        val k = CanvasWebKey("https", 8765, "AAAA", "tok")
        assertEquals("https://127.0.0.1:8765/#t=tok", k.url())
        assertFalse(k.url().substringBefore('#').contains("tok"))
        assertFalse(k.toString().contains("tok"))
        assertFalse(k.toString().contains("AAAA"))
    }

    @Test fun clearsSslPreferencesOnRotate() {
        val loader = Loader()
        val first = CanvasWebKey.next(null, "https", 8765, ours.fingerprint, ::mint)
        assertTrue(applyWebKey(null, first, loader))
        assertEquals(listOf("clear", "load:https://127.0.0.1:8765/"), loader.calls) // on first creation too, and in this order
        loader.calls.clear()
        assertFalse(applyWebKey(first, first, loader))
        assertTrue(loader.calls.isEmpty())
        val rotated = CanvasWebKey.next(first, "https", 8765, theirs.fingerprint, ::mint)
        assertTrue(applyWebKey(first, rotated, loader))
        assertEquals(listOf("clear", "load:https://127.0.0.1:8765/"), loader.calls)
        assertTrue(rotated.token != first.token)
    }
}
