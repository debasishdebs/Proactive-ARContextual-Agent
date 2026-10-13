package com.rokid.context.phone.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Final review M5 (Phase 4), moved here by F14 when the old Config screen was deleted: the API key field — now
 * `TextFieldRow(secret = true)` in SettingsWidgets.kt — must not let the IME learn or suggest the key.
 */
class ApiKeyFieldRulesTest {
    private val src = File("src/main/java/com/rokid/context/phone/ui/settings/SettingsWidgets.kt").readText()
    private val field = src.substringAfter("fun TextFieldRow(").substringBefore("\n}\n")
    private val secretKeyboard = src.substringAfter("SECRET_KEYBOARD =").substringBefore("\n")

    @Test fun apiKeyFieldIsAPasswordFieldWithoutAutocorrect() {
        assertTrue("password keyboard", secretKeyboard.contains("keyboardType = KeyboardType.Password"))
        assertTrue("no autocorrect", secretKeyboard.contains("autoCorrectEnabled = false"))
        assertTrue("secret branch uses it", field.contains("keyboardOptions = if (secret) SECRET_KEYBOARD"))
        assertTrue("masked", field.contains("visualTransformation = if (secret) PasswordVisualTransformation()"))
    }
}
