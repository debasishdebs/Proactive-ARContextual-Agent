package com.rokid.context.phone.canvas.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.canvas.helper.HelperCardState
import com.rokid.context.phone.canvas.helper.PendingConfirmRow

/** A pending job as the card draws it: the row and the whole seconds left of its tap window (the view model drops it at zero). */
data class PendingConfirmUi(val row: PendingConfirmRow, val secondsLeft: Int)

/**
 * The native helper card (3b WP2): accept a pairing, the `py_exec` grant, disconnect. Three callbacks and a state; the view model behind them calls
 * the native actions directly. Hidden while the host has not bound a helper.
 */
@Composable
fun HelperCard(state: HelperCardState, onAcceptPairing: () -> Unit, onGrantPyExec: (Boolean) -> Unit, onDisconnect: () -> Unit) {
    if (!state.available) return
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().testTag("helper_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Laptop helper", style = MaterialTheme.typography.titleSmall)
            if (!state.paired) {
                Text("Run a Python function over your photos on your own laptop. Tap Accept pairing, then type the code on the laptop.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                if (state.pairingCode != null) {
                    Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                    Text(state.pairingCode, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("helper_pair_code"))
                    Text(CanvasHomeText.countdown(state.pairingSecondsLeft), style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.testTag("helper_pair_left"))
                }
                Button(onClick = onAcceptPairing, modifier = Modifier.testTag("helper_accept_pairing")) { Text(if (state.pairingCode != null) "New code" else "Accept pairing") }
            } else {
                Text(state.helperName.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("helper_name"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow Python experiments", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.grantPyExec, onCheckedChange = onGrantPyExec, modifier = Modifier.testTag("helper_grant_switch"))
                }
                Text("Every run still needs your tap here.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("helper_disconnect")) { Text("Disconnect") }
            }
        }
    }
}

/**
 * The per-run confirmation card (user decision 2026-10-23 evening): each pending job with a countdown, Confirm and Decline. It holds only the pending list and the two
 * bound callbacks, nothing else; no key, tag or hash is ever shown or passed. Hidden when nothing is pending.
 */
@Composable
fun HelperConfirmCard(pending: List<PendingConfirmUi>, onConfirm: (String) -> Unit, onCancel: (String) -> Unit) {
    if (pending.isEmpty()) return
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().testTag("confirm_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Run on your laptop?", style = MaterialTheme.typography.titleSmall)
            pending.forEach { p ->
                val id = p.row.jobId
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val n = p.row.itemCount
                    Text("Python on $n ${if (n == 1) "frame" else "frames"} · ${p.row.sourceChars} characters", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("confirm_row_$id"))
                    Text(CanvasHomeText.countdown(p.secondsLeft), style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.testTag("confirm_left_$id"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onConfirm(id) }, modifier = Modifier.testTag("confirm_ok_$id")) { Text("Confirm") }
                        OutlinedButton(onClick = { onCancel(id) }, modifier = Modifier.testTag("confirm_decline_$id")) { Text("Decline") }
                    }
                }
            }
        }
    }
}
