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
        val hits = kt(File(ui, "settings")).filter { it.name != "SettingsDeps.kt" }
            .flatMap { f -> banned.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("$hits", hits.isEmpty())
    }

    @Test fun onlyDestinationFilesCollectState() {
        // F13: AppShell.kt collects the shell's own bluetoothMissing flag (Phase 4); it creates no ViewModel.
        // Phase 7 Stream 0: a route composable (`fun XxxRoute(`) is destination wiring moved out of Destinations.kt.
        val route = Regex("""fun [A-Za-z]+Route\(""")
        val hits = kt(ui).filter {
            val text = it.readText()
            "collectAsStateWithLifecycle" in text && !it.name.endsWith("Destinations.kt") && it.name != "AppShell.kt" && !route.containsMatchIn(text)
        }.map { it.name }
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

    @Test fun theLegacyConfigPathIsGone() {
        val roots = listOf(File("src/main"), File("../services/orchestration/src/main"))
        val hits = roots.flatMap { r -> kt(r) }.filter { f -> listOf("LegacyConfigBridge", "ConfigHubScreen", "LegacyConfigScreen", "setProvider(").any { it in f.readText() } }.map { it.name }
        assertTrue("$hits", hits.isEmpty())
    }

    /**
     * Task 16 carry + fix round 1 (m9): `getWithLegacySecret` opens the Keystore; only the off-Main legacy-key seed may
     * call it, on any receiver (an injected reader too). Its declarations don't count.
     */
    @Test fun onlyTheLegacyKeySeedReadsTheLegacySecret() {
        val roots = listOf(File("src/main"), File("../services/orchestration/src/main"), File("../services/ai/src/main"), File("../services/memory/src/main"))
        val hits = roots.flatMap { r -> kt(r) }.filter { f -> f.readLines().any { "getWithLegacySecret(" in it && "fun getWithLegacySecret(" !in it } }.map { it.name }
        assertTrue("$hits", hits == listOf("LegacyKeySeed.kt"))
        val oldName = roots.flatMap { r -> kt(r) }.filter { f -> Regex("""AppConfigStore\.get\(|config\.get\(context""").containsMatchIn(f.readText()) }.map { it.name }
        assertTrue("$oldName", oldName.isEmpty())
    }
}
