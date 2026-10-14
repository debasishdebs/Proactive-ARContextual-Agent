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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rokid.context.core.ui.components.GradientCircleButton
import com.rokid.context.core.ui.components.GradientHeader
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerHost
import com.rokid.context.phone.ui.actions.ProposalCard

/** A nudge whose source was forgotten (Forget redacts the row; its text is never shown). */
const val REDACTED_NUDGE = "Removed \u2014 a source was forgotten"

/** Home, Ask-first (spec §2.4, mock phone-home.html option B). Stateless. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQuestionChange: (String) -> Unit,
    onAsk: (String) -> Unit,
    onOpenAsk: (String) -> Unit,
    onTogglePause: () -> Unit,
    onBannerAction: (BannerAction) -> Unit,
    onMic: () -> Unit = {},
    onRequestMic: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    /**
     * Home left the composition (navigated away) or the app left the foreground (Home's lifecycle ON_STOP: back on the
     * root, home, app switch, screen off): cancels a phone ask still being spoken. Back on the root Home entry only moves
     * the task to the background (Android 12+), so the composition is not disposed and the ViewModel is not cleared.
     */
    onLeave: () -> Unit = {},
    /** A nudge card opens its detail (`nudge/{id}`); a redacted one is not tappable. */
    onOpenNudge: (String) -> Unit = {},
) {
    val leave by rememberUpdatedState(onLeave)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val stop = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) leave() }
        lifecycle.addObserver(stop)
        onDispose { lifecycle.removeObserver(stop); leave() }
    }
    val glyph = when { state.status.live -> "● "; state.status.paused -> "❚❚ "; else -> "" }
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface).testTag("home_list")) {
        item {
            GradientHeader(title = "Proactive Context", subtitle = glyph + state.status.captureText) {
                // ⏸/▶ follows only the phone pipeline; a glasses pause is shown but not actionable (resume from the phone: Phase 7).
                val onGlasses = state.status.pausedOnGlasses
                val running = state.status.pipelineEnabled
                val label = when { onGlasses -> "Paused on glasses"; running -> "Pause capture"; else -> "Resume capture" }
                IconButton(onClick = onTogglePause, enabled = !onGlasses, modifier = Modifier.clip(CircleShape).background(PcColors.Surface)) {
                    Icon(
                        if (onGlasses || running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = label,
                        tint = if (onGlasses) PcColors.InkSoft else PcColors.Ink,
                    )
                }
            }
        }
        item {
            Text(
                "${com.rokid.context.phone.ui.activity.ActivityModel.plural(state.photosToday, "photo")} today", style = MaterialTheme.typography.labelMedium,
                color = PcColors.InkSoft, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp).testTag("photos_today"),
            )
        }
        item { BannerHost(state.status.banner, onBannerAction) }
        item { AskHero(state, onQuestionChange, onAsk, onOpenAsk, onMic, onRequestMic, onOpenAppSettings) }
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
                    icon = Icons.Outlined.Lightbulb, colors = PcColors.ChipAmber, meta = "Heads up · ${item.timeLabel}",
                    title = if (item.redacted) REDACTED_NUDGE else item.text, body = null,
                    onClick = if (item.redacted) null else ({ onOpenNudge(item.id) }),
                    footer = if (item.redacted) null else ({ ProposalCard(item.id) }),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskHero(
    state: HomeUiState, onQuestionChange: (String) -> Unit, onAsk: (String) -> Unit, onOpenAsk: (String) -> Unit,
    onMic: () -> Unit, onRequestMic: () -> Unit, onOpenAppSettings: () -> Unit,
) {
    SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val v = state.voice
            GradientCircleButton(
                when { v.listening -> Icons.Rounded.Stop; v.thinking -> Icons.Rounded.Close; else -> Icons.Rounded.Mic },
                contentDescription = when { v.listening -> "Stop and send"; v.thinking -> "Cancel"; else -> "Ask by voice" },
                onClick = { when { v.blocked -> onOpenAppSettings(); v.needsPermission -> onRequestMic(); else -> onMic() } },
                enabled = v.enabled,
            )
            Spacer(Modifier.height(12.dp))
            Text("Ask about your day", style = MaterialTheme.typography.titleLarge, color = PcColors.Ink)
            Text(v.hint, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
            if (v.blocked) TextButton(onClick = onOpenAppSettings) { Text(HomeVoiceUi.OPEN_APP_SETTINGS) }
            if (v.partial.isNotBlank()) Text("\u201c${v.partial}\u201d", style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, modifier = Modifier.padding(top = 6.dp))
            v.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.ChipCoral.second, modifier = Modifier.padding(top = 6.dp)) }
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
private fun FeedCard(
    icon: ImageVector, colors: Pair<Color, Color>, meta: String, title: String, body: String?, onClick: (() -> Unit)?,
    footer: (@Composable () -> Unit)? = null,
) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Text(title, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, maxLines = 4, overflow = TextOverflow.Ellipsis)
                footer?.invoke()
            }
        }
    }
}
