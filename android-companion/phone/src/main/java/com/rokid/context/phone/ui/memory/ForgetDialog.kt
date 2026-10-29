package com.rokid.context.phone.ui.memory

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** The Forget confirm dialog (counts, scope, acknowledgement), shared by every previewer that can forget. */
@Composable
fun ForgetDialog(state: MemoryDetailUiState, onConfirm: () -> Unit, onDismiss: () -> Unit, onAck: (Boolean) -> Unit) {
    state.confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (kind == ForgetKind.FACT) ForgetCopy.FACT_TITLE else ForgetCopy.MOMENT_TITLE) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(if (kind == ForgetKind.FACT) ForgetCopy.FACT_BODY else ForgetCopy.MOMENT_BODY)
                    // v0.9.7 slice C: the scope that will really run, in plain words, then the counts (a Scene Forget never runs without them)
                    state.forgetPreview?.let { p ->
                        Text(ForgetScopeCopy.dialogScope(p.effective), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        if (!p.tooLarge) Text(ForgetScopeCopy.counts(p.photos, p.memories, p.marks, p.moments, p.scenesRemoved, p.scenesShrunk))
                        if (!p.tooLarge && p.outside > 0) Text(ForgetScopeCopy.outsideLine(p.outside))
                        if (!p.tooLarge && p.peerResults > 0) Text(ForgetScopeCopy.peerResultsLine(p.peerResults))
                        if (!p.tooLarge && p.needsAck) {
                            Text(ForgetScopeCopy.rebuildWarning(p.peerResults), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                androidx.compose.material3.Checkbox(checked = state.forgetAcked, onCheckedChange = onAck)
                                Text(ForgetScopeCopy.ACK_LABEL)
                            }
                        }
                    }
                    state.message?.let { Text(it, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) } // e.g. "Content changed since the preview. Review again."
                    if (state.forgetPreview?.tooLarge == true) Text(ForgetScopeCopy.TOO_LARGE)
                    if (state.forgetPreviewing) Text(ForgetScopeCopy.COUNTING)
                    if (state.forgetPreviewFailed) Text(ForgetScopeCopy.COUNTS_FAILED)
                }
            },
            confirmButton = { TextButton(onClick = onConfirm, enabled = !state.forgetting && !state.forgetPreviewing && state.forgetConfirmable) { Text(ForgetCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(ForgetCopy.CANCEL) } },
        )
    }
}
