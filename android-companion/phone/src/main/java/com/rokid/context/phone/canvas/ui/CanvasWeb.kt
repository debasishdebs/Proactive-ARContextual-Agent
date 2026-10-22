package com.rokid.context.phone.canvas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.canvas.CanvasHost
import com.rokid.context.phone.canvas.CanvasService
import com.rokid.context.phone.canvas.server.SslDecision
import com.rokid.context.phone.canvas.server.TlsIdentity
import java.io.ByteArrayInputStream

/**
 * The in-app editor may only show the canvas server's own origin; every other link opens in an external browser.
 * The scheme must be the one the server speaks now: https while LAN is on ([secure]), http otherwise.
 */
object CanvasWebPolicy {
    fun allow(url: String, port: Int, secure: Boolean): Boolean {
        val origin = "${if (secure) "https" else "http"}://127.0.0.1:$port"
        if (!url.startsWith(origin)) return false
        val rest = url.substring(origin.length)
        return rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#'
    }
}

/**
 * What the WebView page was loaded for: a change of any part means a new origin, certificate or session, so the page is reloaded
 * and the WebView's retained SSL decisions are cleared (S37). [toString] never shows the token or the fingerprint.
 */
data class CanvasWebKey(val scheme: String, val port: Int, val fingerprint: String?, val token: String) {
    /** The fragment is never sent to the server and the page moves it to sessionStorage; the token is never in a path or query. */
    fun url(): String = "$scheme://127.0.0.1:$port/#t=$token"

    override fun toString() = "CanvasWebKey($scheme, $port)"

    companion object {
        fun needsReload(old: CanvasWebKey?, new: CanvasWebKey): Boolean = old != new

        /** [old] when scheme, port and certificate are unchanged (no new session); otherwise a key with a freshly minted token. */
        fun next(old: CanvasWebKey?, scheme: String, port: Int, fingerprint: String?, mintToken: () -> String): CanvasWebKey =
            if (old != null && old.scheme == scheme && old.port == port && old.fingerprint == fingerprint) old
            else CanvasWebKey(scheme, port, fingerprint, mintToken())
    }
}

/** The two WebView calls a reload needs, so the order is testable without a real WebView. */
internal interface SslResettableLoader {
    fun clearSslPreferences()
    fun loadUrl(url: String)
}

/** On first creation and on any change: forget retained SSL decisions first, then load. Returns whether it loaded. */
internal fun applyWebKey(old: CanvasWebKey?, new: CanvasWebKey, loader: SslResettableLoader): Boolean {
    if (!CanvasWebKey.needsReload(old, new)) return false
    loader.clearSslPreferences()
    loader.loadUrl(new.url())
    return true
}

/**
 * The WebView screen. It loads `<scheme>://127.0.0.1:<bound port>/#t=<token>` (https while LAN is on): the fragment is never sent to
 * the server and the page moves it to sessionStorage. The page is reloaded, with the SSL decisions cleared, whenever the scheme, port,
 * certificate or token changes. No file or content access, no JavaScript bridge; JavaScript is enabled on this WebView only.
 */
@Composable
fun CanvasWebRoute(host: CanvasHost, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by host.status.collectAsStateWithLifecycle()
    val port = status.port
    val scheme = if (status.lan) "https" else "http"
    var key by remember { mutableStateOf<CanvasWebKey?>(null) }
    // Only the service starts the server (and handles refusal and timeout); this screen waits for the status to flip.
    LaunchedEffect(Unit) { CanvasService.start(ctx.applicationContext) }
    DisposableEffect(Unit) { host.screenOpen = true; onDispose { host.screenOpen = false } }

    val web = remember { newCanvasWebView(ctx, { host.status.value.port ?: 0 }, { host.status.value.lan }, { host.tlsIdentity() }) }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    BackHandler { if (web.canGoBack()) web.goBack() else onBack() }
    LaunchedEffect(status.on, port, scheme, status.fingerprint) {
        if (!status.on || port == null) return@LaunchedEffect
        val old = key
        val new = CanvasWebKey.next(old, scheme, port, status.fingerprint) { host.webViewToken() }
        key = new
        applyWebKey(old, new, object : SslResettableLoader {
            override fun clearSslPreferences() = web.clearSslPreferences()
            override fun loadUrl(url: String) = web.loadUrl(url)
        })
    }
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun newCanvasWebView(context: Context, port: () -> Int, secure: () -> Boolean, tls: () -> TlsIdentity?): WebView = WebView(context).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION") run {
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        setGeolocationEnabled(false)
    }
    // No addJavascriptInterface: the page talks to the server over HTTP(S) only.
    webViewClient = CanvasWebClient(context, port, secure, tls)
}

/** The WebView client: own-origin navigation only, and certificate pinning (the only way past a certificate error is our fingerprint). */
internal class CanvasWebClient(
    private val context: Context,
    private val port: () -> Int,
    private val secure: () -> Boolean,
    private val tls: () -> TlsIdentity?,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url.toString()
        if (CanvasWebPolicy.allow(u, port(), secure())) return false
        val scheme = request.url.scheme
        if (scheme == "http" || scheme == "https") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (CanvasWebPolicy.allow(request.url.toString(), port(), secure())) null
        else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Proceeds only when the presented certificate is ours (SHA-256 of its DER equals our fingerprint); every other error cancels. */
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val matches = secure() && runCatching { error.certificate.x509Certificate?.let { tls()?.matches(it) } == true }.getOrDefault(false)
        if (SslDecision.decide(matches)) handler.proceed() else handler.cancel()
    }
}
