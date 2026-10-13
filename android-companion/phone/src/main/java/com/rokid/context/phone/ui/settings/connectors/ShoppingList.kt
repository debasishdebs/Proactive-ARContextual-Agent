package com.rokid.context.phone.ui.settings.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.db.ShoppingItemEntity
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Settings › Shopping list (R-62): open items first; the checkbox toggles done; swipe deletes. DB work on IO. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShoppingRoute(deps: SettingsDeps, back: () -> Unit) {
    val graph by ActionsGraph.ready.collectAsStateWithLifecycle()
    val dao = graph?.shopping
    val items by remember(dao) { dao?.observeAll()?.flowOn(Dispatchers.IO) ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    ShoppingListScreen(
        items,
        onDone = { id, done -> scope.launch(Dispatchers.IO) { runCatching { dao?.setDone(id, done) } } },
        onDelete = { id -> scope.launch(Dispatchers.IO) { runCatching { dao?.delete(id) } } },
        back = back,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(items: List<ShoppingItemEntity>, onDone: (String, Boolean) -> Unit, onDelete: (String) -> Unit, back: () -> Unit) {
    SettingsPage("Shopping list", back, tag = "settings_shopping") {
        if (items.isEmpty()) item { SettingsCard { NoticeText("Nothing on your list", tag = "shopping_empty") } }
        items.sortedBy { it.done }.forEach { it ->
            item(key = it.id) {
                val state = rememberSwipeToDismissBoxState(confirmValueChange = { v -> if (v != SwipeToDismissBoxValue.Settled) onDelete(it.id); true })
                SwipeToDismissBox(state, backgroundContent = { Row(Modifier.fillMaxWidth().background(PcColors.SurfaceSoft)) {} }) {
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = it.done, onCheckedChange = { d -> onDone(it.id, d) }, modifier = Modifier.testTag("done_${it.id}"))
                            Text(
                                label(it), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp),
                                color = if (it.done) PcColors.InkSoft else PcColors.Ink, textDecoration = if (it.done) TextDecoration.LineThrough else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun label(i: ShoppingItemEntity): String {
    val qty = i.qty?.let { q -> if (q % 1.0 == 0.0) q.toLong().toString() else q.toString() }
    return listOfNotNull(qty, i.unit, i.name).joinToString(" ")
}
