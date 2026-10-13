package com.rokid.context.phone.ui.settings.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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

/**
 * Settings > Shopping list (R-62): open items first; the checkbox toggles done; swipe (or the TalkBack "Delete" action)
 * removes an item, with an Undo snackbar that re-inserts the same row. DB work on IO.
 */
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
        onRestore = { item -> scope.launch(Dispatchers.IO) { runCatching { dao?.insertIgnore(listOf(item)) } } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    items: List<ShoppingItemEntity>, onDone: (String, Boolean) -> Unit, onDelete: (String) -> Unit, back: () -> Unit,
    onRestore: (ShoppingItemEntity) -> Unit = {},
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun remove(item: ShoppingItemEntity) {
        onDelete(item.id)
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar("Removed ${label(item)}", actionLabel = "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(item)
        }
    }
    Box(Modifier.fillMaxSize()) {
        SettingsPage("Shopping list", back, tag = "settings_shopping") {
            if (items.isEmpty()) {
                item { SettingsCard { NoticeText("Nothing on your list", tag = "shopping_empty") } }
            } else {
                item { Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { NoticeText("Swipe an item away to remove it", tag = "shopping_hint") } }
            }
            items.sortedBy { it.done }.forEach { it ->
                item(key = it.id) {
                    // confirmValueChange runs more than once per swipe: delete only on the first call.
                    val fired = remember { booleanArrayOf(false) }
                    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                        if (v != SwipeToDismissBoxValue.Settled && !fired[0]) { fired[0] = true; remove(it) }
                        true
                    })
                    SwipeToDismissBox(
                        state,
                        modifier = Modifier.testTag("shopping_row_${it.id}").semantics {
                            customActions = listOf(CustomAccessibilityAction("Delete") { if (!fired[0]) { fired[0] = true; remove(it) }; true })
                        },
                        backgroundContent = {
                            Row(
                                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp).background(PcColors.ChipCoral.first).padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                            ) { Icon(Icons.Outlined.Delete, contentDescription = null, tint = PcColors.ChipCoral.second) }
                        },
                    ) {
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
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

private fun label(i: ShoppingItemEntity): String {
    val qty = i.qty?.let { q -> if (q % 1.0 == 0.0) q.toLong().toString() else q.toString() }
    return listOfNotNull(qty, i.unit, i.name).joinToString(" ")
}
