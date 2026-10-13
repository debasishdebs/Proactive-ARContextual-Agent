package com.rokid.context.phone.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R-20 for the Settings tree: screens and ViewModels get everything through SettingsDeps/PhoneDeps. */
class SettingsSourceRulesTest {
    private val ui = File("src/main/java/com/rokid/context/phone/ui")
    private fun kt(dir: File) = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    @Test fun settingsFilesNeverReachForGlobalsOrContext() {
        val banned = listOf("AiGraph.get(", "MemoryGraph.get(", "HubGraph.get(", "LocalContext", "AppConfigStore", "runBlocking", "delay(")
        val hits = kt(File(ui, "settings")).filter { it.name != "SettingsDeps.kt" && it.name != "LegacyConfig.kt" }
            .flatMap { f -> banned.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("$hits", hits.isEmpty())
    }

    @Test fun onlyDestinationFilesCollectState() {
        // F13: AppShell.kt collects the shell's own bluetoothMissing flag (Phase 4); it creates no ViewModel.
        val hits = kt(ui).filter { "collectAsStateWithLifecycle" in it.readText() && !it.name.endsWith("Destinations.kt") && it.name != "AppShell.kt" }.map { it.name }
        assertTrue("$hits", hits.isEmpty())
    }

    /** F14 (keeps Phase 4's ApiKeyFieldRulesTest rule): a secret field never lets the IME learn or suggest the key. */
    @Test fun secretTextFieldsUseAPasswordKeyboardWithoutAutocorrect() {
        val src = File(ui, "settings/SettingsWidgets.kt").readText()
        val secret = src.substringAfter("SECRET_KEYBOARD =").substringBefore("\n")
        assertTrue("password keyboard", secret.contains("keyboardType = KeyboardType.Password"))
        assertTrue("no autocorrect", secret.contains("autoCorrectEnabled = false"))
        assertTrue("secret branch", src.contains("keyboardOptions = if (secret) SECRET_KEYBOARD"))
        assertTrue("masked", src.contains("if (secret) PasswordVisualTransformation()"))
    }
}
