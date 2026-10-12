package com.rokid.context.ui.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Final review M5: the API key field must not let the IME learn or suggest the key (source rule; no semantics for keyboard type). */
class ApiKeyFieldRulesTest {
    private val src = File("src/main/java/com/rokid/context/ui/config/ConfigHubScreen.kt").readText()
    private val field = src.substringAfter("value = state.apiKeyMasked").substringBefore("OutlinedTextField(")

    @Test fun apiKeyFieldIsAPasswordFieldWithoutAutocorrect() {
        assertTrue("password keyboard", field.contains("keyboardType = KeyboardType.Password"))
        assertTrue("no autocorrect", field.contains("autoCorrectEnabled = false"))
        assertTrue("masked", field.contains("visualTransformation = PasswordVisualTransformation()"))
    }
}
