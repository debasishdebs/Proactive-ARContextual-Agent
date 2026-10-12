package com.rokid.context.phone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GradientCircleButton
import com.rokid.context.core.ui.components.GradientHeader
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerHost

/** Home, Ask-first (spec §2.4, mock phone-home.html option B). Stateless. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQuestionChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onOpenAsk: (String) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
) {
    var expandedNudge by rememberSaveable { mutableStateOf<String?>(null) }
    val glyph = when { state.status.live -> "● "; state.status.paused -> "❚❚ "; else -> "" }
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("home_list")) {
        item {
            GradientHeader(title = "Proactive Context", subtitle = glyph + state.status.captureText) {
                val label = if (state.status.paused) "Resume capture" else "Pause capture"
                IconButton(onClick = onTogglePause, modifier = Modifier.clip(CircleShape).background(PcColors.Surface)) {
                    Icon(if (state.status.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = label, tint = PcColors.Ink)
                }
            }
        }
        item { BannerHost(state.status.banner, onBannerAction) }
        item { AskHero(state, onQuestionChange, onAsk, onOpenAsk) }
        item { SectionLabel("For you") }
        if (state.feed.isEmpty()) {
            item {
                Text(
                    "Answers and heads-ups will appear here.", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        items(state.feed, key = { it.id }) { item ->
            when (item) {
                is FeedItem.Ask -> FeedCard(
                    icon = Icons.Outlined.ChatBubbleOutline, colors = PcColors.ChipSky, meta = item.timeLabel,
                    title = "You asked “${item.question}”", body = item.answer, onClick = { onOpenAsk(item.id) },
                )
                is FeedItem.Nudge -> FeedCard(
                    icon = Icons.Outlined.Lightbulb, colors = PcColors.ChipAmber, meta = "Heads up · ${item.timeLabel}", title = item.text,
                    body = if (expandedNudge == item.id && !item.redacted && item.why.isNotBlank()) item.why else null,
                    onClick = { expandedNudge = if (expandedNudge == item.id) null else item.id },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskHero(state: HomeUiState, onQuestionChange: (String) -> Unit, onAsk: (String) -> Unit, onOpenAsk: (String) -> Unit) {
    SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GradientCircleButton(Icons.Rounded.Mic, contentDescription = "Ask by voice", onClick = {}, enabled = state.voiceAvailable)
            Spacer(Modifier.height(12.dp))
            Text("Ask about your day", style = MaterialTheme.typography.titleLarge, color = PcColors.Ink)
            Text(
                if (state.voiceAvailable) "or tap your glasses" else "Type a question below, or tap your glasses",
                style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.suggestions.forEach { QuestionPill(it, enabled = !state.asking) { onAsk(it) } }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.question,
                onValueChange = onQuestionChange,
                placeholder = { Text("Type a question") },
                singleLine = true,
                enabled = !state.asking,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onAsk(state.question) }),
                trailingIcon = {
                    IconButton(onClick = { onAsk(state.question) }, enabled = !state.asking && state.question.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send question")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.asking) Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(top = 8.dp))
            state.askError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.ChipCoral.second, modifier = Modifier.padding(top = 8.dp)) }
            state.lastAnswer?.let { a ->
                SoftCard(Modifier.padding(top = 12.dp).fillMaxWidth(), onClick = { onOpenAsk(a.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(a.question, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                        Text(a.answer, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text("See sources ›", style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = PcColors.Ink,
        modifier = Modifier.clip(shape).background(PcColors.SurfaceSoft).border(1.dp, PcColors.Line, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun FeedCard(icon: ImageVector, colors: Pair<Color, Color>, meta: String, title: String, body: String?, onClick: () -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Text(title, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
