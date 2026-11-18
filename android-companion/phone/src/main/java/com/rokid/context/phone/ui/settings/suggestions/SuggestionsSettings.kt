package com.rokid.context.phone.ui.settings.suggestions

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveForgetListener
import com.rokid.context.orchestration.proactive.ProactiveGraph
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.Templates
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The "why am I seeing this" sheet's content, in words, built from a record's ids and fixed tokens (never stored text). */
data class WhyUi(val family: String, val trigger: String, val sources: List<String>, val reasons: List<String>)

/** One would-show suggestion as the Developer list shows it. [text] is the template rendered from the LIVE sources just now. */
data class ShadowRowUi(val id: String, val key: String, val ts: Long, val text: String, val sourceLabel: String, val why: WhyUi)

data class SuggestionsUiState(
    val master: Boolean = false, val placeRecall: Boolean = true, val replay: Boolean = false,
    val rows: List<ShadowRowUi> = emptyList(), val neverCount: Int = 0, val counters: List<Pair<String, Int>> = emptyList(),
)

/** What the screen needs (production: [AndroidSuggestionsControl]; tests: a fake). */
interface SuggestionsControl {
    val state: StateFlow<SuggestionsUiState>
    fun setMaster(on: Boolean)
    fun setPlaceRecall(on: Boolean)
    fun setReplay(on: Boolean)
    /** Dismiss: removes the would-show. */
    fun dismiss(id: String)
    /** "Never suggest this": the key joins the never list and the would-show goes. */
    fun never(id: String)
    fun clearNever()
    fun refresh()
}

object SuggestionsModel {
    const val TITLE = "Suggestions (shadow)"
    const val MASTER_TITLE = "Suggestions"
    const val MASTER_NOTE = "Off by default. In shadow mode PCA only works out what it WOULD suggest from your own places, notes and marks, and lists it here. Nothing is shown on your glasses, in a notification or in Nexus, and nothing leaves your phone."
    const val SAFETY = "Suggestions can miss things. Do not rely on them for safety. No suggestion never means everything is fine."
    const val PLACE_TITLE = "Place recall"
    const val PLACE_NOTE = "“Last visit” reminders from your place memories, pinned-place notes and marks."
    const val REPLAY_TITLE = "Developer replay"
    const val REPLAY_NOTE = "Lets a laptop replay a day over your stored data (POST /api/proactive/replay). Read-only; needs Developer mode."
    const val EMPTY = "Nothing would have been suggested yet."

    fun triggerWords(t: TriggerKind): String = when (t) {
        TriggerKind.TICK -> "A periodic check (every 15 minutes)"
        TriggerKind.PLACE_ARRIVAL -> "You arrived at a place you have been before"
        TriggerKind.MOMENT_END -> "A moment ended"
    }

    fun familyWords(f: ProactiveFamily): String = when (f) { ProactiveFamily.PLACE_RECALL -> "Place recall" }

    /** Built from the record's tokens and the rendered sources: the family, the trigger, the sources with their times, and the score factors in words. */
    fun why(r: SuggestionRecord, card: RenderedCard, zone: ZoneId): WhyUi {
        val sources = card.sources.map { s ->
            val kind = when (s.kind) { "memory" -> "Place memory"; "pin" -> "Your pin note"; "mark" -> "A mark you made"; else -> "A source" }
            s.tsMs?.let { "$kind · ${Templates.date(it, zone)}" } ?: kind
        }
        val reasons = ArrayList<String>()
        for (f in r.factors) when {
            f == "arrived" -> reasons += "You were at this place a few minutes ago."
            f.startsWith("visits=") -> f.removePrefix("visits=").toIntOrNull()?.let { reasons += "You have looked at it $it ${if (it == 1) "time" else "times"} before." }
            f == "pin_note" -> reasons += "You wrote a note for this place."
            f.startsWith("marks=") -> f.removePrefix("marks=").toIntOrNull()?.let { reasons += "You made $it ${if (it == 1) "mark" else "marks"} on earlier visits." }
        }
        reasons += "Score ${"%.2f".format(java.util.Locale.ROOT, r.score)} (needs ${"%.2f".format(java.util.Locale.ROOT, com.rokid.context.orchestration.proactive.ProactiveRanker.THRESHOLD)})."
        return WhyUi(familyWords(r.family), triggerWords(r.trigger), sources, reasons)
    }

    private val COUNTER_WORDS = mapOf("runs" to "Runs", "would_show" to "Would have shown", "drop_no_candidates" to "Nothing to suggest")

    fun counterLabel(name: String): String = COUNTER_WORDS[name] ?: name.removePrefix("drop_").replace('_', ' ').replaceFirstChar { it.uppercase() }.let { if (name.startsWith("drop_")) "Stopped: $it" else it }
}

class AndroidSuggestionsControl(private val app: Context) : SuggestionsControl {
    private val settings = ProactiveSettings.of(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = MutableStateFlow(SuggestionsUiState(settings.master.value, settings.placeRecall.value, settings.replay.value))
    override val state: StateFlow<SuggestionsUiState> = ui.asStateFlow()

    override fun setMaster(on: Boolean) { settings.setMaster(on); refresh() }
    override fun setPlaceRecall(on: Boolean) { settings.setPlaceRecall(on); refresh() }
    override fun setReplay(on: Boolean) { settings.setReplay(on); refresh() }
    override fun dismiss(id: String) { ProactiveGraph.store(app).withdraw(id); refresh() }
    override fun never(id: String) { val s = ProactiveGraph.store(app); s.records().firstOrNull { it.id == id }?.let { s.addNever(it.key) }; s.withdraw(id); refresh() }
    override fun clearNever() { val s = ProactiveGraph.store(app); s.never().forEach(s::removeNever); refresh() }

    override fun refresh() {
        scope.launch {
            val store = ProactiveGraph.store(app)
            val gen = ProactiveGraph.generator(app)
            val now = System.currentTimeMillis(); val zone = ZoneId.systemDefault()
            // a record whose sources are gone is withdrawn here too (a pin removed in Settings is no Forget event)
            runCatching { ProactiveForgetListener({ store }, { ProactiveGraph.liveness(app) }).sweep() }
            val rows = store.records().mapNotNull { r -> gen.render(r, now)?.let { c -> ShadowRowUi(r.id, r.key, r.ts, c.text, c.sourceLabel, SuggestionsModel.why(r, c, zone)) } }
            ui.value = SuggestionsUiState(settings.master.value, settings.placeRecall.value, settings.replay.value, rows, store.never().size, store.counters().toList().sortedBy { it.first }.map { SuggestionsModel.counterLabel(it.first) to it.second })
        }
    }
}

@Composable
fun SuggestionsRoute(control: SuggestionsControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { control.refresh() }
    SuggestionsScreen(state, back, control::setMaster, control::setPlaceRecall, control::setReplay, control::dismiss, control::never, control::clearNever)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(
    state: SuggestionsUiState, back: () -> Unit, onMaster: (Boolean) -> Unit, onPlace: (Boolean) -> Unit, onReplay: (Boolean) -> Unit,
    onDismiss: (String) -> Unit, onNever: (String) -> Unit, onClearNever: () -> Unit,
) {
    var whyId by remember { mutableStateOf<String?>(null) }
    val shown = state.rows.firstOrNull { it.id == whyId }
    SettingsPage(SuggestionsModel.TITLE, back, tag = "suggestions_settings") {
        item {
            SettingsCard {
                SwitchRow(SuggestionsModel.MASTER_TITLE, SuggestionsModel.MASTER_NOTE, state.master, onMaster, tag = "suggestions_master")
                NoticeText(SuggestionsModel.SAFETY, tag = "suggestions_safety")
            }
        }
        item {
            SettingsCard {
                SwitchRow(SuggestionsModel.PLACE_TITLE, SuggestionsModel.PLACE_NOTE, state.placeRecall, onPlace, tag = "suggestions_place", enabled = state.master)
                SwitchRow(SuggestionsModel.REPLAY_TITLE, SuggestionsModel.REPLAY_NOTE, state.replay, onReplay, tag = "suggestions_replay")
            }
        }
        item {
            SettingsCard {
                Text("Counters", style = MaterialTheme.typography.titleMedium)
                if (state.counters.isEmpty()) NoticeText("None yet.", tag = "suggestions_counters_empty")
                state.counters.forEachIndexed { i, (label, n) -> Text("$label: $n", Modifier.fillMaxWidth().testTag("suggestions_counter_$i")) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Never suggest list: ${state.neverCount}", Modifier.weight(1f).testTag("suggestions_never_count"))
                    SecondaryAction("Clear", onClearNever, tag = "suggestions_never_clear", enabled = state.neverCount > 0)
                }
            }
        }
        item { Text("Would have shown", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
        if (state.rows.isEmpty()) item { SettingsCard { NoticeText(SuggestionsModel.EMPTY, tag = "suggestions_empty") } }
        state.rows.forEachIndexed { i, r ->
            item(key = r.id) {
                SettingsCard {
                    Column(Modifier.testTag("suggestions_row_$i"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("suggestions_text_$i"))
                        Text(r.sourceLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("suggestions_source_$i"))
                        Row(Modifier.fillMaxWidth()) {
                            SecondaryAction("Why am I seeing this", { whyId = r.id }, tag = "suggestions_why_$i")
                            SecondaryAction("Dismiss", { onDismiss(r.id) }, tag = "suggestions_dismiss_$i")
                        }
                    }
                }
            }
        }
    }
    if (shown != null) ModalBottomSheet(onDismissRequest = { whyId = null }) {
        WhyContent(shown.why, onNever = { onNever(shown.id); whyId = null }, onClose = { whyId = null })
    }
}

@Composable
fun WhyContent(why: WhyUi, onNever: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("suggestions_why_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Why am I seeing this", style = MaterialTheme.typography.titleLarge)
        Text("Kind: ${why.family}", modifier = Modifier.testTag("why_family"))
        Text("Started by: ${why.trigger}", modifier = Modifier.testTag("why_trigger"))
        Text("Built from", style = MaterialTheme.typography.titleMedium)
        why.sources.forEachIndexed { i, s -> Text("• $s", modifier = Modifier.testTag("why_source_$i")) }
        Text("Why it scored", style = MaterialTheme.typography.titleMedium)
        why.reasons.forEachIndexed { i, s -> Text("• $s", modifier = Modifier.testTag("why_reason_$i")) }
        PrimaryAction("Never suggest this", onNever, tag = "why_never")
        SecondaryAction("Close", onClose, tag = "why_close")
    }
}
