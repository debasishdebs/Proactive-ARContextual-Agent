package com.rokid.context.phone.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors

/** Ask detail (spec §2.4). Stateless; [proposalCard] (the live ProposalCard) replaces the read-only proposal line when given. */
@Composable
fun AskDetailScreen(
    state: AskDetailUiState, onBack: () -> Unit, onRate: (Boolean) -> Unit, onOpenMemory: (String) -> Unit,
    proposalCard: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(PcColors.Surface).verticalScroll(rememberScrollState())) {
        ScreenHeader("Answer", onBack)
        if (state.loading) return@Column
        if (!state.found) {
            Text("This answer is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Text("YOU ASKED · ${state.timeLabel}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        Text(state.question, style = MaterialTheme.typography.titleLarge, color = PcColors.Ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(state.answer, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                if (!state.redacted) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { onRate(true) }, modifier = Modifier.semantics { selected = state.rating == 1 }) {
                            Icon(if (state.rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Helpful", tint = PcColors.MintDeep)
                        }
                        IconButton(onClick = { onRate(false) }, modifier = Modifier.semantics { selected = state.rating == -1 }) {
                            Icon(if (state.rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Not helpful", tint = PcColors.ChipCoral.second)
                        }
                    }
                }
            }
        }
        if (proposalCard != null) proposalCard() else state.proposal?.let { p ->
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Text("${p.title} · ${p.state}", style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, modifier = Modifier.padding(16.dp))
            }
        }
        if (!state.redacted) {
            SectionLabel("Based on")
            if (state.sources.isEmpty() && state.missingSources == 0) {
                Text("No saved memories were cited", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
            }
            state.sources.forEach { s ->
                SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = { onOpenMemory(s.memoryId) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrameThumb(s.framePath, size = 52.dp)
                        Text(s.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        ConfidenceChip(s.confidence)
                    }
                }
            }
            if (state.missingSources > 0) {
                val n = state.missingSources
                Text(
                    "$n source${if (n == 1) " is" else "s are"} no longer available", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
