package com.rokid.context.phone.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameImage
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import java.time.LocalDate

/** Memory detail (spec §2.4). Forget fact / Forget moment run the Phase 7 cascade (spec §6.1.1) after a confirm dialog. */
@Composable
fun MemoryDetailScreen(
    state: MemoryDetailUiState,
    onBack: () -> Unit,
    onTrust: () -> Unit,
    onStartEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onOpenFrames: (LocalDate, String) -> Unit,
    onForget: (ForgetKind) -> Unit = {},
    onConfirmForget: () -> Unit = {},
    onDismissForget: () -> Unit = {},
) {
    state.confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = onDismissForget,
            title = { Text(if (kind == ForgetKind.FACT) ForgetCopy.FACT_TITLE else ForgetCopy.MOMENT_TITLE) },
            text = { Text(if (kind == ForgetKind.FACT) ForgetCopy.FACT_BODY else ForgetCopy.MOMENT_BODY) },
            confirmButton = { TextButton(onClick = onConfirmForget, enabled = !state.forgetting) { Text(ForgetCopy.CONFIRM) } },
            dismissButton = { TextButton(onClick = onDismissForget) { Text(ForgetCopy.CANCEL) } },
        )
    }
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState())) {
        ScreenHeader("Memory", onBack)
        if (state.loading) return@Column
        if (!state.found) {
            Text("This memory is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        if (state.framePath != null) {
            ZoomableFrame(state.framePath, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(260.dp))
            val date = state.frameDate
            val id = state.frameId
            if (date != null && id != null) {
                TextButton(onClick = { onOpenFrames(date, id) }, modifier = Modifier.padding(start = 8.dp)) { Text("Open this day's photos", color = PcColors.MintDeep) }
            }
        }
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val draft = state.draft
                if (draft != null) {
                    OutlinedTextField(value = draft, onValueChange = onDraftChange, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton("Save", onClick = onSaveEdit)
                        TextButton(onClick = onCancelEdit) { Text("Cancel") }
                    }
                } else {
                    Text(state.text, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
                }
                if (state.tags.isNotEmpty()) Text(state.tags.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ConfidenceChip(state.confidence)
                    Text("${state.tierLabel} · ${state.reviewLabel}${if (state.trusted) " · Trusted" else ""}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                }
                Text(state.createdLabel, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                state.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep) }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GradientButton(if (state.trusted) "Trusted" else "Trust", onClick = onTrust, enabled = state.editable && !state.trusted && state.draft == null)
            OutlinedButton(onClick = onStartEdit, enabled = state.editable && state.draft == null) { Text("Edit") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onForget(ForgetKind.FACT) }, enabled = state.editable && !state.forgetting && state.draft == null) { Text("Forget fact") }
            if (state.frameId != null) {
                OutlinedButton(onClick = { onForget(ForgetKind.MOMENT) }, enabled = !state.forgetting && state.draft == null) { Text("Forget moment") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ZoomableFrame(path: String, modifier: Modifier) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = Zoom.clamp(scale * zoom)
        val next = offset + pan
        offset = if (scale == Zoom.MIN) Offset.Zero else Offset(Zoom.clampPan(next.x, scale, size.width.toFloat()), Zoom.clampPan(next.y, scale, size.height.toFloat()))
    }
    Box(modifier.clip(RoundedCornerShape(20.dp)).onSizeChanged { size = it }.transformable(transform)) {
        FrameImage(path, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}
