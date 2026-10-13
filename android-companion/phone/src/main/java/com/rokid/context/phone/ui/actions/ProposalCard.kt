package com.rokid.context.phone.ui.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.Resolve
import kotlinx.coroutines.launch

/**
 * The proposal card under an Ask or nudge (R-62, spec §2.4): observes the origin's proposal. PENDING offers Add / No
 * thanks (the engine's CAS decides; a late tap says what happened), EXECUTED offers Undo for 10 s (and "Turn on" when it
 * can't alert: notifications off), SUPPRESSED links to
 * Settings › Actions. Renders nothing when the origin has no proposal.
 */
@Composable
fun ProposalCard(
    originId: String, modifier: Modifier = Modifier, source: ProposalCardSource? = null, onEnable: (() -> Unit)? = null,
    onFixNotifications: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val src = source ?: remember(app) { GraphCardSource.forApp(app) }
    val ui by remember(originId, src) { src.observe(originId) }.collectAsState(initial = null)
    val p = ui ?: return
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var undoOffered by remember(p.id, p.canUndo) { mutableStateOf(p.canUndo) }
    LaunchedEffect(p.id, p.canUndo) {
        if (p.canUndo) { kotlinx.coroutines.delay(src.undoRemainingMs(p.id)); undoOffered = false }
    }
    val enable = onEnable ?: LocalOpenActionsSettings.current
    fun answer(yes: Boolean) = scope.launch {
        val r = src.respond(p.id, yes)
        if (r is Resolve.Lost) snack.showSnackbar(ActionsUi.lateText(r.current))
    }

    SoftCard(modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(ActionsUi.typeLabel(p.type).uppercase(), style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
            when (p.state) {
                ProposalState.SUPPRESSED -> {
                    Text("Suggested: ${p.summary}", style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                    TextButton(onClick = { enable?.invoke() }) { Text("Enable in Settings") }
                }
                ProposalState.PENDING -> {
                    Text(p.summary, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { answer(true) }) { Text("Add") }
                        OutlinedButton(onClick = { answer(false) }) { Text("No thanks") }
                    }
                }
                else -> {
                    Text(p.summary, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.warning ?: stateText(p), style = MaterialTheme.typography.titleSmall, color = PcColors.InkSoft, modifier = Modifier.padding(top = 12.dp))
                        if (p.state == ProposalState.EXECUTED && undoOffered) TextButton(onClick = {
                            scope.launch { if (!src.undo(p.id)) snack.showSnackbar(UNDO_FAILED) }
                        }) { Text("Undo") }
                        if (p.state == ProposalState.EXECUTED && p.warning != null) TextButton(onClick = {
                            onFixNotifications?.invoke() ?: openNotificationSettings(context)
                        }) { Text("Turn on") }
                    }
                }
            }
            SnackbarHost(snack)
        }
    }
}

private const val UNDO_FAILED = "Couldn't undo"

/** This app's notification settings (the fix for an "Added, but notifications are off" card). */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { /* no settings screen on this build */ }
}

private fun stateText(p: ProposalUi): String = when (p.state) {
    ProposalState.EXECUTING -> "Adding\u2026"
    ProposalState.EXECUTED -> "Added"
    ProposalState.DECLINED -> if (p.undone) "Undone" else "Declined"
    ProposalState.EXPIRED -> "Expired"
    ProposalState.FAILED -> p.failure ?: ActionsUi.lateText(ProposalState.FAILED)
    ProposalState.PENDING, ProposalState.SUPPRESSED -> ""
}
