package com.rokid.context.phone.canvas.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.canvas.CanvasPublisher
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.canvas.CanvasHost
import com.rokid.context.phone.canvas.CanvasService
import com.rokid.context.phone.canvas.CanvasStatus
import com.rokid.context.phone.canvas.PipelineRow
import com.rokid.context.phone.canvas.PublishRow
import com.rokid.context.phone.canvas.RunRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

const val LAN_NOTE = "Encrypted with a certificate made on this phone. Turns off after 30 min idle."
const val LAN_UNAVAILABLE = "Secure LAN isn't available on this phone"
const val LOCKED_TEXT = "Locked \u2014 tap New code"

data class CanvasHomeUi(
    val status: CanvasStatus = CanvasStatus(),
    /** The pairing code is shown only while LAN is on or after "Open in phone browser". */
    val showCode: Boolean = false,
    val secondsLeft: Int = 0,
    val wifiAvailable: Boolean = true,
    val pipelines: List<PipelineRow> = emptyList(),
    val runs: List<RunRow> = emptyList(),
    val publishActive: Boolean = false,
    /** The publish the "Roll back" button undoes (named, so the click cannot hit a newer one). */
    val newestPublish: PublishRow? = null,
    /** Shown once in a snackbar, then cleared by [CanvasHomeViewModel.messageShown]. */
    val message: String? = null,
)

object CanvasHomeText {
    fun statusLine(s: CanvasStatus): String = when {
        !s.on || s.port == null -> "Off"
        s.lan && s.lanIp != null -> "On \u00b7 LAN ${s.lanIp}:${s.port} \u00b7 turns off after 30 min idle"
        else -> "On \u00b7 127.0.0.1:${s.port}"
    }

    fun countdown(seconds: Int): String = "Expires in ${seconds / 60}:%02d".format(seconds % 60)

    /** The button's label names what it undoes. */
    fun rollbackLabel(p: PublishRow): String = when {
        p.kind == "interpreter" && p.mode == "SHADOW" -> "Roll back Shadow pipeline"
        p.kind == "interpreter" -> "Roll back live pipeline"
        else -> "Roll back live"
    }

    fun rollbackMessage(o: CanvasPublisher.Outcome): String = when (o) {
        is CanvasPublisher.Outcome.Published -> "Rolled back"
        is CanvasPublisher.Outcome.Blocked -> o.reasons.joinToString("\n").ifBlank { "Roll back was blocked" }
    }
}

class CanvasHomeViewModel(
    private val host: CanvasHost,
    private val ensureService: () -> Unit,
    private val openUrl: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private data class Local(
        val showCode: Boolean = false, val nowMs: Long = 0L, val pipelines: List<PipelineRow> = emptyList(), val runs: List<RunRow> = emptyList(),
        val publishActive: Boolean = false, val newest: PublishRow? = null, val message: String? = null,
    )

    private val local = MutableStateFlow(Local(nowMs = clock()))

    val state: StateFlow<CanvasHomeUi> = combine(host.status, local) { s, l ->
        val left = if (s.code != null) ((s.codeExpiresAt - l.nowMs) / 1000).toInt().coerceAtLeast(0) else 0
        CanvasHomeUi(
            status = s, showCode = (l.showCode || s.lan) && s.code != null, secondsLeft = left, wifiAvailable = s.lanIp != null,
            pipelines = l.pipelines, runs = l.runs, publishActive = l.publishActive, newestPublish = l.newest, message = l.message ?: s.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CanvasHomeUi(status = host.status.value))

    /** Screen shown: the server is needed, so the service starts it. */
    fun onStart() {
        host.screenOpen = true
        ensureService()
        host.refreshNetwork()
        reload()
    }

    fun onStop() { host.screenOpen = false }

    /** Once a second while the screen is up: the countdown, and a fresh code when the shown one expired. */
    fun tick() {
        val now = clock()
        local.update { it.copy(nowMs = now) }
        val s = host.status.value
        // After a lockout nothing mints a code: the user taps "New code" (S17).
        if ((local.value.showCode || s.lan) && s.on && !s.pairLocked && (s.code == null || now >= s.codeExpiresAt)) host.pairingCode()
        host.refreshNetwork()
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val h = host.home()
                local.update { it.copy(pipelines = h.pipelines, runs = h.runs, publishActive = h.publishActive, newest = h.newestPublish) }
            } catch (c: CancellationException) { throw c } catch (e: Exception) { /* the lists stay as they were */ }
        }
    }

    fun setLan(on: Boolean) {
        if (on) ensureService()
        host.setLan(on)
        if (on) local.update { it.copy(showCode = true) }
        else local.update { it.copy(showCode = false) }
    }

    fun openInBrowser() {
        ensureService()
        if (!host.status.value.on) host.start(host.status.value.lan)
        val st = host.status.value
        val port = st.port ?: return
        val code = host.pairingCode()
        if (code == null) {
            local.update { it.copy(message = "Pairing is locked. Tap New code first.") }
            return
        }
        local.update { it.copy(showCode = true) }
        openUrl("${st.scheme}://127.0.0.1:$port/#pair=${code.code}") // a fragment: never sent to the server, never in a Referer
    }

    fun rollback() {
        val target = local.value.newest ?: return // nothing was shown, so there is nothing to undo by id
        viewModelScope.launch {
            val msg = try {
                CanvasHomeText.rollbackMessage(host.rollback(target.id))
            } catch (c: CancellationException) { throw c } catch (e: Exception) { "Roll back failed" }
            local.update { it.copy(message = msg) }
            reload()
        }
    }

    fun signOutAll() {
        host.revokeAll()
        local.update { it.copy(message = "Signed out all browsers") }
    }

    /** The explicit "New code": the only way out of a pairing lockout. */
    fun newCode() {
        host.newPairingCode()
        local.update { it.copy(showCode = true) }
    }

    /** New certificate: the LAN restarts on it and every browser is signed out. */
    fun newCertificate() {
        host.rotateCertificate()
        local.update { it.copy(message = "New certificate. Every browser was signed out.") }
    }

    fun messageShown() { local.update { it.copy(message = null) } }
}

@Composable
fun CanvasHomeScreen(
    ui: CanvasHomeUi,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenBrowser: () -> Unit,
    onLan: (Boolean) -> Unit,
    onOpenPipeline: (String) -> Unit,
    onRollback: () -> Unit,
    onSignOutAll: () -> Unit,
    onMessageShown: () -> Unit,
    onNewCode: () -> Unit = {},
    onNewCertificate: () -> Unit = {},
) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snack.showSnackbar(it); onMessageShown() }
    }
    Box(Modifier.fillMaxSize().background(PcColors.SurfaceSoft)) {
        LazyColumn(Modifier.fillMaxSize().testTag("canvas_scroll")) {
            item { ScreenHeader("Experiment canvas", onBack) }
            item {
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(CanvasHomeText.statusLine(ui.status), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("canvas_status"))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onOpenEditor, modifier = Modifier.testTag("canvas_open_editor")) { Text("Open editor") }
                            OutlinedButton(onClick = onOpenBrowser, modifier = Modifier.testTag("canvas_open_browser")) { Text("Open in phone browser") }
                        }
                    }
                }
            }
            item {
                SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Use from a laptop", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Switch(
                                checked = ui.status.lan, onCheckedChange = onLan, enabled = ui.status.lanAvailable && (ui.wifiAvailable || ui.status.lan),
                                modifier = Modifier.testTag("canvas_lan_switch"),
                            )
                        }
                        Text(LAN_NOTE, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                        if (!ui.status.lanAvailable) Text(LAN_UNAVAILABLE, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.testTag("canvas_lan_unavailable"))
                        else if (!ui.wifiAvailable && !ui.status.lan) Text("Connect to Wi-Fi to use this.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                        ui.status.lanUrl?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_lan_url")) }
                        val fingerprint = ui.status.fingerprint
                        if (ui.status.lan && fingerprint != null) {
                            var all by remember { mutableStateOf(false) }
                            val groups = fingerprint.split(' ')
                            Text("Certificate fingerprint", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                            Text(
                                (if (all) groups else groups.take(8)).joinToString(" "), style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.testTag("canvas_fingerprint"),
                            )
                            TextButton(onClick = { all = !all }, modifier = Modifier.testTag("canvas_fingerprint_toggle")) { Text(if (all) "Show less" else "Show all") }
                            Text("Your browser will warn once. Check that the fingerprint in its certificate details matches.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                            OutlinedButton(onClick = onNewCertificate, modifier = Modifier.testTag("canvas_new_certificate")) { Text("New certificate") }
                        }
                        if (ui.status.on && ui.status.pairLocked) {
                            Text(LOCKED_TEXT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_locked"))
                            Button(onClick = onNewCode, modifier = Modifier.testTag("canvas_new_code")) { Text("New code") }
                        } else if (ui.showCode && ui.status.code != null) {
                            Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                            Text(ui.status.code, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("canvas_code"))
                            Text(CanvasHomeText.countdown(ui.secondsLeft), style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.testTag("canvas_code_left"))
                        }
                    }
                }
            }
            item { SectionLabel("Pipelines") }
            item {
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        if (ui.pipelines.isEmpty()) Text("No pipelines yet", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
                        ui.pipelines.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickableRow { onOpenPipeline(p.id) }.padding(16.dp).testTag("canvas_pipeline_${p.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("${p.versions} version" + if (p.versions == 1) "" else "s", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                                }
                                if (p.liveVersion != null) Text("LIVE v${p.liveVersion}", style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep)
                            }
                        }
                    }
                }
            }
            item { SectionLabel("Last runs") }
            item {
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Column {
                        if (ui.runs.isEmpty()) Text("No runs yet", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
                        ui.runs.forEach { r ->
                            Column(Modifier.padding(16.dp)) {
                                Text(r.status.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${r.photos} photos \u00b7 ${r.calls} calls \u00b7 " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(r.startedAt)),
                                    style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.newestPublish?.let { newest -> OutlinedButton(onClick = onRollback, modifier = Modifier.fillMaxWidth().testTag("canvas_rollback")) { Text(CanvasHomeText.rollbackLabel(newest)) } }
                    OutlinedButton(onClick = onSignOutAll, modifier = Modifier.fillMaxWidth().testTag("canvas_sign_out")) { Text("Sign out all browsers") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** Route wiring: the ViewModel, the lifecycle (server needed while shown) and the once-a-second tick. */
@Composable
fun CanvasHomeRoute(host: CanvasHost, onBack: () -> Unit, onOpenEditor: () -> Unit, onOpenPipeline: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm = viewModel {
        CanvasHomeViewModel(
            host, ensureService = { CanvasService.start(ctx.applicationContext) },
            openUrl = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        )
    }
    val ui by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.onStart() else if (e == Lifecycle.Event.ON_STOP) vm.onStop()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.onStop() }
    }
    LaunchedEffect(vm) { while (true) { delay(1_000); vm.tick() } }
    CanvasHomeScreen(
        ui, onBack, onOpenEditor, onOpenBrowser = vm::openInBrowser, onLan = vm::setLan, onOpenPipeline = onOpenPipeline,
        onRollback = vm::rollback, onSignOutAll = vm::signOutAll, onMessageShown = vm::messageShown,
        onNewCode = vm::newCode, onNewCertificate = vm::newCertificate,
    )
}
