package com.rokid.context.phone.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.memory.ForgetDialog

/** The previewer's words for the one Forget action: which Forget the target runs. */
fun forgetLabel(target: ForgetTarget): String = when (target) {
    is ForgetTarget.Fact -> "Forget fact"
    is ForgetTarget.Photo -> "Forget photo"
    is ForgetTarget.Moment, is ForgetTarget.Scene -> "Forget"
}

/**
 * The one previewer. Layout only: the sections of [item] in order (each draws itself and owns its actions), the Forget action when the item has something to forget, and the loading / not-found states.
 * It never looks at what kind of item it shows. The Forget dialog and everything behind it are the memory detail's (shared); nothing here deletes.
 */
@Composable
fun PreviewScreen(
    state: PreviewUiState,
    host: PreviewHost,
    onBack: () -> Unit,
    onForget: (ForgetTarget) -> Unit,
    onConfirmForget: () -> Unit = {},
    onDismissForget: () -> Unit = {},
    onAckForget: (Boolean) -> Unit = {},
) {
    ForgetDialog(host.detail, onConfirmForget, onDismissForget, onAckForget)
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState()).testTag("preview_screen")) {
        ScreenHeader("Preview", onBack)
        val item = state.item
        when {
            state.loading -> Unit
            item == null -> Text("This item is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp).testTag("preview_missing"))
            else -> {
                item.sections().forEach { it.Content(host) }
                item.forgetTarget()?.let { target ->
                    OutlinedButton(onClick = { onForget(target) }, enabled = !host.detail.forgetting && host.detail.draft == null, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("preview_forget")) { Text(forgetLabel(target)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
