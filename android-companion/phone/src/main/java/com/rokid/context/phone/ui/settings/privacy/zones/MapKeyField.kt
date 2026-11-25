package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.privacy.zones.online.MapKeyControl

object MapKeyWords {
    const val HELP = "Needed for map tiles; stored on this phone only."
    const val INVALID = "That does not look like a map key (letters, digits, - and _ only)."
    const val FAILED = "Could not store the key on this phone."
}

/**
 * The map key entry of the street-map card: write-only (the stored key is never read back into the field), hidden by default with Show, paste-friendly, Clear removes the stored key.
 * [onChanged] runs after a save or clear so the screen rebuilds its map wiring with the new key.
 */
@Composable
internal fun MapKeyField(keys: MapKeyControl, onChanged: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        OutlinedTextField(
            value = text, onValueChange = { text = it; error = null }, singleLine = true, isError = error != null,
            label = { Text(if (keys.hasKey) "Map key (set)" else "Map key") },
            supportingText = { Text(error ?: MapKeyWords.HELP) },
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { TextButton({ shown = !shown }, Modifier.testTag("map_key_show")) { Text(if (shown) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth().testTag("map_key_field"),
        )
        PrimaryAction("Save map key", {
            if (!com.rokid.context.phone.ui.settings.privacy.zones.online.MapKeyStore.valid(text.trim())) error = MapKeyWords.INVALID
            else if (keys.save(text)) { text = ""; shown = false; error = null; onChanged() }
            else error = MapKeyWords.FAILED
        }, tag = "map_key_save", enabled = text.isNotBlank())
        if (keys.hasStoredKey) SecondaryAction("Clear map key", { keys.clear(); text = ""; error = null; onChanged() }, tag = "map_key_clear")
    }
}
