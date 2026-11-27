package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors

/** One settings screen: header + scrolling cards (spec §2.3 spacing). */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, tag: String, actions: @Composable () -> Unit = {}, listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(), content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).testTag(tag), state = listState) {
        item { ScreenHeader(title, onBack, actions) }
        content()
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    SoftCard(modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String, enabled: Boolean = true) {
    // The whole row is the switch, so TalkBack reads "<title>, switch, on" (the Switch itself takes no input).
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A labelled single choice (announced "<title>: <value>"); the menu items are tagged "<tag>_<index>". */
@Composable
fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit, tag: String, enabled: Boolean = true, disabled: Set<Int> = emptySet(), disabledReason: String? = null) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            val value = options.getOrElse(selected) { "—" }
            TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(tag).semantics { contentDescription = "$title: $value" }) { Text(value) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    val off = i in disabled
                    // A greyed-out option says why to TalkBack, not just in a notice below the menu.
                    val why = if (off && disabledReason != null) Modifier.semantics { contentDescription = "$o \u2014 $disabledReason"; stateDescription = "Unavailable" } else Modifier
                    DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) }, enabled = !off, modifier = Modifier.testTag("${tag}_$i").then(why))
                }
            }
        }
    }
}

/** F14 / Phase 4 ApiKeyFieldRulesTest: a secret never goes to the IME's learning or suggestions. */
private val SECRET_KEYBOARD = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

@Composable
fun TextFieldRow(
    label: String, value: String, onChange: (String) -> Unit, tag: String,
    singleLine: Boolean = true, placeholder: String? = null, secret: Boolean = false, enabled: Boolean = true,
    supporting: String? = null, isError: Boolean = false, number: Boolean = false, onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, enabled = enabled, isError = isError,
        placeholder = placeholder?.let { { Text(it) } }, supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (secret) SECRET_KEYBOARD else KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, imeAction = if (onDone != null) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default),
        keyboardActions = if (onDone != null) androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }) else androidx.compose.foundation.text.KeyboardActions.Default,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
fun NoticeText(text: String, error: Boolean = false, tag: String = "notice") {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) PcColors.ChipCoral.second else PcColors.InkSoft, modifier = Modifier.testTag(tag))
}

@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) =
    GradientButton(text, onClick, Modifier.fillMaxWidth().testTag(tag), enabled = enabled)

@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) =
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(text) }
