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
import androidx.compose.material.icons.outlined.LocationOn
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.rokid.context.phone.ui.banner.BannerPager
import com.rokid.context.phone.ui.actions.ProposalCard

/** A nudge whose source was forgotten (Forget redacts the row; its text is never shown). */
const val REDACTED_NUDGE = "Removed \u2014 a source was forgotten"

/** P3 (retention v3): the kept summary of a nudge or answer whose source details are no longer available (older builds removed expired details; clean-up now deletes no text and Forget is the only way text goes). The summary is never replaced. */
fun expiredSourcesText(n: Int): String = "$n older detail${if (n == 1) " is" else "s are"} no longer available; the summary is kept"

/** M5: an answer whose source was forgotten (the question is kept, the answer is erased). Shown with a button, never re-answered by itself. */
const val ANSWER_FROM_FORGOTTEN = "Answered from a forgotten fact"
const val ANSWER_ASKED_AGAIN = "Answered from a forgotten fact \u2014 asked again"
const val ASK_AGAIN = "Ask again"

/** The Ask again button: its own accessibility label names the question, so a screen reader does not hear "Ask again" six times with no context. */
@Composable
fun AskAgainButton(question: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier.semantics { contentDescription = "$ASK_AGAIN: $question" }) { Text(ASK_AGAIN) }
}

/** Home, Ask-first (spec §2.4, mock phone-home.html option B). Stateless. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    /** M5: "Ask again" on an answer whose source was forgotten. */
    onAskAgain: (String) -> Unit = {},
    /** A nudge card opens its detail (`nudge/{id}`); a redacted one is not tappable. */
    onOpenNudge: (String) -> Unit = {},
    /** G3: closes the limited-mode banner ("Review" opens the app's system settings, where its permissions are). */
    onDismissLimited: () -> Unit = {},
    /** v3.1: what the buttons on a suggestion card do. */
    onSuggestion: SuggestionActionsUi = SuggestionActionsUi.None,
    /** B2: the "Last place card" row opens the Place Guide list. */
    onOpenPlaceCards: () -> Unit = {},
) {
    var whyId by remember { mutableStateOf<String?>(null) }
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
        item { BannerPager(state.status.banners, onBannerAction) }
        if (state.limited) item {
            com.rokid.context.core.ui.components.Banner(
                tone = com.rokid.context.core.ui.components.BannerTone.SLATE, title = LimitedMode.TITLE,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("limited_banner"),
                actionLabel = "Review", onAction = onOpenAppSettings, onDismiss = onDismissLimited,
            )
        }
        item { AskHero(state, onQuestionChange, onAsk, onOpenAsk, onMic, onRequestMic, onOpenAppSettings) }
        state.lastPlaceCard?.let { p ->
            item(key = "last_place_card") {
                Column(Modifier.testTag("last_place_card")) {
                    FeedCard(icon = Icons.Outlined.LocationOn, colors = PcColors.ChipViolet, meta = p.meta, title = p.title, body = p.body, onClick = onOpenPlaceCards)
                }
            }
        }
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
                    title = "You asked “${item.question}”", body = if (item.forgotten) (if (item.askedAgain) ANSWER_ASKED_AGAIN else ANSWER_FROM_FORGOTTEN) else item.answer, onClick = { onOpenAsk(item.id) },
                    footer = if (item.forgotten && !item.askedAgain) ({ AskAgainButton(item.question, { onAskAgain(item.id) }, Modifier.testTag("ask_again_${item.id}")) }) else null,
                )
                is FeedItem.Suggestion -> FeedCard(
                    icon = Icons.Outlined.LocationOn, colors = PcColors.ChipViolet, meta = "Suggestion · ${item.timeLabel}",
                    title = item.title, body = item.line + "\n" + item.sourceLabel, onClick = null,
                    footer = { SuggestionButtons(item, onSuggestion, onWhy = { whyId = item.id }) },
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
    val shownWhy = state.feed.firstOrNull { it.id == whyId } as? FeedItem.Suggestion
    if (shownWhy != null) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { whyId = null }) {
        com.rokid.context.phone.ui.settings.suggestions.WhyContent(shownWhy.why, onNever = { onSuggestion.never(shownWhy.id); whyId = null }, onClose = { whyId = null })
    }
}

/** What the buttons of a suggestion card do (the phone feed). */
class SuggestionActionsUi(val dismiss: (String) -> Unit, val snooze: (String) -> Unit, val never: (String) -> Unit, val thumb: (String, Boolean) -> Unit) {
    companion object { val None = SuggestionActionsUi({}, {}, {}, { _, _ -> }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionButtons(item: FeedItem.Suggestion, a: SuggestionActionsUi, onWhy: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onWhy, modifier = Modifier.testTag("suggestion_why_${item.id}")) { Text("Why am I seeing this") }
        TextButton(onClick = { a.dismiss(item.id) }, modifier = Modifier.testTag("suggestion_dismiss_${item.id}")) { Text("Dismiss") }
        TextButton(onClick = { a.snooze(item.id) }, modifier = Modifier.testTag("suggestion_snooze_${item.id}")) { Text("Snooze 1 day") }
        TextButton(onClick = { a.thumb(item.id, true) }, modifier = Modifier.testTag("suggestion_up_${item.id}")) { Text("Useful") }
        TextButton(onClick = { a.thumb(item.id, false) }, modifier = Modifier.testTag("suggestion_down_${item.id}")) { Text("Not useful") }
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
            state.reply?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, modifier = Modifier.padding(top = 8.dp)) }
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
