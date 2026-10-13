package com.rokid.context.phone.ui.settings.prompts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Agent flows spec section 4: a full-screen editor for a prompt template or schema. It holds its own text (saved across
 * recreation) and hands it back only on Done, so the caller's existing edit path and gates decide what it means.
 * Cancel and Back leave the caller's draft untouched; Back with edited text first asks "Discard changes?". Tapping a chip
 * inserts `{{name}}` at the cursor.
 */
@Composable
fun FullScreenTextEditor(title: String, initial: String, chips: List<String>, onDone: (String) -> Unit, onCancel: () -> Unit, tag: String) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = { if (field.text != initial) { confirmDiscard = true } else { onCancel() } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag(tag)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onCancel, Modifier.testTag("${tag}_cancel")) { Text("Cancel") }
                    TextButton({ onDone(field.text) }, Modifier.testTag("${tag}_done")) { Text("Done") }
                }
                if (chips.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { name ->
                            SuggestionChip(onClick = { field = insert(field, "{{$name}}") }, label = { Text("{{$name}}") }, modifier = Modifier.testTag("${tag}_chip_$name"))
                        }
                    }
                }
                TextField(
                    value = field, onValueChange = { field = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).testTag("${tag}_field"),
                )
            }
        }
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits in full screen will be lost.") },
            confirmButton = { TextButton({ confirmDiscard = false; onCancel() }, Modifier.testTag("${tag}_discard")) { Text("Discard") } },
            dismissButton = { TextButton({ confirmDiscard = false }, Modifier.testTag("${tag}_keep_editing")) { Text("Keep editing") } },
        )
    }
}

/** Replaces the selection (or inserts at the caret) with [text] and puts the caret after it. */
internal fun insert(v: TextFieldValue, text: String): TextFieldValue {
    val start = v.selection.min.coerceIn(0, v.text.length)
    val end = v.selection.max.coerceIn(start, v.text.length)
    return TextFieldValue(v.text.replaceRange(start, end, text), TextRange(start + text.length))
}
