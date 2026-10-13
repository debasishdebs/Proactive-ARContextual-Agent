package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.ui.theme.PcColors

/** spec §2.3 `CodeBox`: monospace prompt/schema editor. [onValueChange] null = read-only (selectable). */
@Composable
fun CodeBox(
    value: String,
    onValueChange: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    label: String? = null,
    minLines: Int = 6,
    isError: Boolean = false,
    tag: String = "code_box",
) {
    val shape = RoundedCornerShape(12.dp)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = PcColors.Ink)
    val box = Modifier.fillMaxWidth().heightIn(min = (minLines * 18).dp).clip(shape).background(PcColors.SurfaceSoft)
        .border(1.dp, if (isError) PcColors.ChipCoral.second else PcColors.Line, shape).padding(12.dp).testTag(tag)
    Column(modifier) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(bottom = 6.dp))
        if (onValueChange == null) {
            SelectionContainer { Text(value.ifEmpty { " " }, style = style, modifier = box) }
        } else {
            // Keeps the caret across edits and starts it at the end (the String overload starts at 0).
            var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
            // Texts this field emitted (most recent 32). A parent value in the record is a (possibly lagging) echo;
            // one outside it is an external change (restore, normalisation, rejection) and replaces the field.
            val emitted = remember { ArrayDeque<String>() }
            var seen by remember { mutableStateOf(value) }
            if (seen != value) {
                seen = value
                // Phase 5 T15 carry: the record follows the newest emitted text and clears once the parent has caught up.
                val at = emitted.lastIndexOf(value)
                when {
                    value == field.text -> emitted.clear() // caught up: nothing of ours is still in flight
                    at >= 0 -> repeat(at + 1) { emitted.removeFirst() } // a lagging echo: everything up to it has arrived
                    else -> { emitted.clear(); field = TextFieldValue(value, TextRange(value.length)) } // an external change
                }
            }
            BasicTextField(
                value = field,
                onValueChange = { next -> val changed = next.text != field.text; field = next; if (changed) { emitted.addLast(next.text); while (emitted.size > 32) emitted.removeFirst(); onValueChange(next.text) } },
                textStyle = style, cursorBrush = SolidColor(PcColors.MintDeep), modifier = box,
            )
        }
    }
}
