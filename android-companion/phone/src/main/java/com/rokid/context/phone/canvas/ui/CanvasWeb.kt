package com.rokid.context.phone.canvas.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import java.io.ByteArrayInputStream

/** The in-app editor may only show the canvas server's own origin; every other link opens in an external browser. */
object CanvasWebPolicy {
    fun allow(url: String, port: Int): Boolean {
        val origin = "http://127.0.0.1:$port"
        if (!url.startsWith(origin)) return false
        val rest = url.substring(origin.length)
        return rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#'
    }
}

/**
 * The WebView screen. It loads `http://127.0.0.1:<bound port>/#t=<token>`: the fragment is never sent to the server and the
 * page moves it to sessionStorage. No file or content access, no JavaScript bridge; JavaScript is enabled on this WebView only.
 */
@Composable
fun CanvasWebRoute(host: CanvasHost, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by host.status.collectAsStateWithLifecycle()
    val port = status.port
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { CanvasService.start(ctx.applicationContext); if (!host.status.value.on) host.start(host.status.value.lan) }
    LaunchedEffect(status.on, port) { if (url == null && status.on && port != null) url = "http://127.0.0.1:$port/#t=${host.webViewToken()}" }
    DisposableEffect(Unit) { host.screenOpen = true; onDispose { host.screenOpen = false } }

    val web = remember { newCanvasWebView(ctx) { host.status.value.port ?: 0 } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    BackHandler { if (web.canGoBack()) web.goBack() else onBack() }
    LaunchedEffect(url) { url?.let { web.loadUrl(it) } }
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) {
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun newCanvasWebView(context: Context, port: () -> Int): WebView = WebView(context).apply {
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
    // No addJavascriptInterface: the page talks to the server over HTTP only.
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (CanvasWebPolicy.allow(u, port())) return false
            val scheme = request.url.scheme
            if (scheme == "http" || scheme == "https") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (CanvasWebPolicy.allow(request.url.toString(), port())) null
            else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
