package com.rokid.context.phone.ui.settings.flows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.ai.Role
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.flow.AgentFlows
import com.rokid.context.ai.flow.Facet
import com.rokid.context.ai.flow.FlowNode
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.ai.AiLabels
import com.rokid.context.phone.ui.settings.ai.RoleBindingCard
import com.rokid.context.phone.ui.settings.ai.RolesViewModel
import com.rokid.context.phone.ui.settings.retrieval.RetrievalControls
import com.rokid.context.phone.ui.settings.retrieval.RetrievalViewModel
import com.rokid.context.phone.ui.shell.Routes
import androidx.compose.runtime.collectAsState

/**
 * The `settings/flows/view` destination body: the flow view plus the step sheet. [roles] and [retrieval] supply the
 * same ViewModels Models by role and Settings > Retrieval use, so the sheet saves through their write paths and validation.
 * Open editor closes the sheet and hands `settings/prompts/{id}` to [navigate].
 */
@Composable
fun FlowViewRoute(
    vm: FlowViewModel,
    roles: @Composable () -> RolesViewModel,
    retrieval: @Composable () -> RetrievalViewModel,
    onBack: () -> Unit,
    navigate: (String) -> Unit,
    editors: Map<String, @Composable () -> Unit> = FacetEditors.all,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    /** "flowId/nodeId" of the open step, or "system" for the System chip; saved so the sheet survives recreation. */
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    FlowViewScreen(state, onBack, onNode = { n -> open = if (n == SYSTEM_STEP) SYSTEM_STEP.id else state.sections.firstOrNull { s -> s.flow.nodes.any { it === n } }?.let { "${it.flow.id}/${n.id}" } }, onLink = vm::addFlow)
    val node = open?.let { key ->
        if (key == SYSTEM_STEP.id) SYSTEM_STEP
        else state.sections.firstOrNull { it.flow.id == key.substringBefore('/') }?.flow?.nodes?.firstOrNull { it.id == key.substringAfter('/') }
    }
    if (node != null && node.isAiStep) {
        StepSheet(node, state.prompts, roles, retrieval, editors, onOpenEditor = { key -> open = null; navigate(Routes.prompt(key.name)) }, onDismiss = { open = null })
    }
}

/** One tab per facet, in the node's facet order. Model and Retrieval are labelled as shared (they belong to a role or the app), and so is a prompt that several steps use. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepSheet(
    node: FlowNode,
    prompts: Map<PromptKey, PromptUi>,
    roles: @Composable () -> RolesViewModel,
    retrieval: @Composable () -> RetrievalViewModel,
    editors: Map<String, @Composable () -> Unit>,
    onOpenEditor: (PromptKey) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("step_sheet")) {
        var tab by rememberSaveable(node.id) { mutableIntStateOf(0) }
        val selected = tab.coerceIn(0, node.facets.size - 1)
        Text(node.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        TabRow(selectedTabIndex = selected) {
            node.facets.forEachIndexed { i, f ->
                Tab(selected = i == selected, onClick = { tab = i }, text = { Text(facetName(f)) }, modifier = Modifier.testTag("step_tab_${facetName(f)}"))
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val f = node.facets[selected]) {
                is Facet.Prompt -> PromptTab(f.key, prompts[f.key], onOpenEditor)
                is Facet.Model -> ModelTab(f.role, roles())
                Facet.Retrieval -> RetrievalTab(retrieval())
                is Facet.Custom -> editors[f.editorId]?.invoke() ?: Pad { NoticeText("Not available in this version", tag = "step_custom_unavailable") }
            }
        }
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) = Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun PromptTab(key: PromptKey, prompt: PromptUi?, onOpenEditor: (PromptKey) -> Unit) = Pad {
    val users = AgentFlows.nodesUsing(key).map { it.second.title }
    if (users.size > 1) {
        Text("Prompt ${key.label} \u00b7 shared by ${users.size} steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("step_prompt_header"))
        Text("Used by: ${users.distinct().joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.testTag("step_prompt_users"))
    } else {
        Text(key.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("step_prompt_header"))
    }
    Text(prompt?.let { "Active v${it.version}" } ?: FacetText.prompt(null), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("step_prompt_version"))
    val vars = prompt?.variables.orEmpty()
    Text("Variables: " + if (vars.isEmpty()) "none" else vars.joinToString(", ") { "{{$it}}" },
        style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.testTag("step_prompt_variables"))
    PrimaryAction("Open editor", { onOpenEditor(key) }, tag = "step_open_editor")
}

@Composable
private fun ModelTab(role: Role, vm: RolesViewModel) {
    val users = AgentFlows.nodesUsing(role).map { it.second.title }
    Pad {
        Text("Model for ${AiLabels.role(role)} \u00b7 shared by ${users.size} step${if (users.size == 1) "" else "s"}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("step_model_header"))
        Text("Used by: ${users.distinct().joinToString(", ")}", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.testTag("step_model_users"))
    }
    val state by vm.state.collectAsState()
    state.rows.firstOrNull { it.role == role }?.let { r ->
        RoleBindingCard(r, vm::setEndpoint, vm::setModel, vm::setFallback, vm::setTemperature, vm::setMaxTokens, onClear = { vm.clear(it) })
    }
}

@Composable
private fun RetrievalTab(vm: RetrievalViewModel) {
    Pad {
        Text("Retrieval settings \u00b7 shared by every search step", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("step_retrieval_header"))
    }
    val state by vm.state.collectAsState()
    RetrievalControls(state, vm::setMode, vm::setFusion, vm::setWeight, vm::setRrfK, vm::setTopK, vm::setRecency, vm::setHalfLife, vm::setMinConfidence)
}
