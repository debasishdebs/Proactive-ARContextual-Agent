package com.rokid.context.phone.ui.settings.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rokid.context.ai.Role
import com.rokid.context.phone.ui.list.ActionResult
import com.rokid.context.phone.ui.list.ItemAction
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.list.ListScreen
import com.rokid.context.phone.ui.list.ListSource
import com.rokid.context.phone.ui.shell.Routes

/** What the Agent flows list needs from a flow. Stand-in for the AgentFlows registry (stream A); D backs it with `AgentFlows.all`. */
data class FlowSummary(val id: String, val title: String, val purpose: String, val aiSteps: Int, val roles: List<Role>)

interface FlowSummaries { fun summaries(): List<FlowSummary> }

/**
 * Production stand-in until the AgentFlows registry merges: the day-one flows as the plan's A1 encodes them
 * (Capture: extract, critic, reprocess, embed, remember, critic review, re-summarise; Ask: search, answer; Nudge: nudge).
 * D replaces this with summaries derived from `AgentFlows.all`.
 */
object DayOneFlowSummaries : FlowSummaries {
    override fun summaries() = listOf(
        FlowSummary("capture", "Capture", "Turns glasses photos and things you say into memories.", 7,
            listOf(Role.VISION_EXTRACT, Role.CRITIC, Role.SUMMARIZE, Role.EMBED, Role.EXPLICIT_REMEMBER)),
        FlowSummary("ask", "Ask", "Answers your questions from your memories.", 2, listOf(Role.EMBED, Role.ANSWER)),
        FlowSummary("nudge", "Nudge", "Suggests something useful after new memories land.", 1, listOf(Role.NUDGE)),
    )
}

/** Settings -> AI -> Agent flows: one row per flow, in registry order; rows are multi-selectable. */
class FlowsListSource(
    private val summaries: FlowSummaries,
    private val models: suspend (Role) -> String?,
    private val onOpen: (String) -> Unit,
) : ListSource {
    override val title = "Agent flows"
    override val searchHint = "Search agent flows"
    override val sortable = false
    override val selectable = true

    override suspend fun load(): List<ListItem> = summaries.summaries().map { f ->
        ListItem(f.id, f.title, subtitle = f.purpose, trailingText = stepsLine(f.aiSteps, f.roles.map { models(it) }))
    }

    override fun actionFor(item: ListItem) = ItemAction { onOpen(item.id); ActionResult.Silent }

    companion object {
        const val NOT_SET = "Not set"
        private const val MAX_MODELS = 2

        /** "N AI steps \u00b7 <distinct model names, max 2 then +k>"; each unbound role (null) reads "Not set", once. */
        fun stepsLine(aiSteps: Int, models: List<String?>): String {
            val names = models.map { it?.takeIf(String::isNotBlank) ?: NOT_SET }.distinct()
            val shown = names.take(MAX_MODELS).joinToString(", ") + if (names.size > MAX_MODELS) " +${names.size - MAX_MODELS}" else ""
            val steps = if (aiSteps == 1) "1 AI step" else "$aiSteps AI steps"
            return if (names.isEmpty()) steps else "$steps \u00b7 $shown"
        }
    }
}

private val IdsSaver = listSaver<MutableState<Set<String>>, String>(save = { it.value.toList() }, restore = { mutableStateOf(it.toSet()) })

/** The `settings/flows` destination body; [navigate] receives `settings/flows/view?ids=...` routes. The selection lives here for the session. */
@Composable
fun FlowsListRoute(summaries: FlowSummaries, models: suspend (Role) -> String?, onBack: () -> Unit, navigate: (String) -> Unit, onMessage: (String) -> Unit = {}) {
    val go by rememberUpdatedState(navigate)
    var selected by rememberSaveable(saver = IdsSaver) { mutableStateOf(emptySet()) }
    val source = remember(summaries) { FlowsListSource(summaries, models) { id -> go(Routes.flowsView(listOf(id))) } }
    ListScreen(
        source, onBack = onBack, onMessage = onMessage, onOpenSelected = { ids -> go(Routes.flowsView(ids)) },
        initiallySelected = selected, onSelectionChange = { selected = it },
    )
}
