package com.rokid.context.phone.ui.settings.connectors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.memory.db.NoteEntity
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings > Actions & tools > Notes ("note that…"): newest first, each with its date. Delete removes a note with an
 * Undo snackbar that re-inserts the same row; Copy puts its text on the clipboard. DB work on IO.
 */
@Composable
fun NotesRoute(back: () -> Unit) {
    val graph by ActionsGraph.ready.collectAsStateWithLifecycle()
    val dao = graph?.notes
    val notes by remember(dao) { dao?.observeAll()?.flowOn(Dispatchers.IO) ?: emptyFlow() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    NotesScreen(
        notes,
        onDelete = { id -> scope.launch(Dispatchers.IO) { runCatching { dao?.delete(id) } } },
        onRestore = { n -> scope.launch(Dispatchers.IO) { runCatching { dao?.insertIgnore(n) } } },
        onCopy = { text -> runCatching { clipboard.setText(AnnotatedString(text)) } },
        back = back,
    )
}

@Composable
fun NotesScreen(
    notes: List<NoteEntity>, onDelete: (String) -> Unit, onRestore: (NoteEntity) -> Unit, onCopy: (String) -> Unit, back: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun say(text: String, undo: NoteEntity? = null) {
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            val r = snack.showSnackbar(text, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && undo != null) onRestore(undo)
        }
    }
    Box(Modifier.fillMaxSize()) {
        SettingsPage("Notes", back, tag = "settings_notes") {
            if (notes.isEmpty()) {
                item { SettingsCard { NoticeText("No notes yet. Say \"note that…\" to your glasses.", tag = "notes_empty") } }
            }
            notes.sortedByDescending { it.createdAt }.forEach { n ->
                item(key = n.id) {
                    SettingsCard {
                        Row(Modifier.testTag("note_row_${n.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n.text, style = MaterialTheme.typography.bodyLarge, color = PcColors.Ink)
                                Text(noteDateText(n.createdAt, zone), style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft, modifier = Modifier.padding(top = 2.dp))
                            }
                            IconButton(onClick = { onCopy(n.text); say("Copied") }, modifier = Modifier.testTag("note_copy_${n.id}")) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy note")
                            }
                            IconButton(onClick = { onDelete(n.id); say("Note deleted", undo = n) }, modifier = Modifier.testTag("note_delete_${n.id}")) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete note")
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

internal fun noteDateText(ms: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(ms).atZone(zone))
