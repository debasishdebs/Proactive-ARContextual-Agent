package com.rokid.context.phone.ui.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source rules from spec §1.4 / R-07 / R-20 (unit tests run with the module directory as working dir). */
class ShellSourceRulesTest {
    private val main = File("src/main/java/com/rokid/context")

    @Test fun mainActivityHasNoPollingLoopNoBridgeAndNoGodComposable() {
        val src = File(main, "MainActivity.kt").readText()
        for (banned in listOf("delay(", "while (true)", "RokidBridgeService", ".bridge.", "@Composable", "mutableStateOf")) {
            assertFalse("MainActivity.kt must not contain '$banned'", src.contains(banned))
        }
        assertTrue(src.contains("AppShell("))
    }

    /** Carried rulings: Phase 3 deletes the bridge package (no reference may survive the merge); the VM owns the API-key buffer. */
    @Test fun mainActivityRequestsBluetoothAndHoldsNoBridgeOrKeyState() {
        val src = File(main, "MainActivity.kt").readText()
        assertFalse("MainActivity.kt must not mention bridge", src.contains("bridge", ignoreCase = true))
        assertFalse("MainActivity.kt must not hold apiKeyInput", src.contains("apiKeyInput"))
        // Legacy purges run once per launch, not again on every configuration change.
        val guard = src.indexOf("if (savedInstanceState == null)")
        assertTrue("purges must sit behind savedInstanceState == null", guard in 0 until src.indexOf("QueueRuntimeStore.purgeSynthetic"))
        assertTrue("MainActivity.kt must request BLUETOOTH_CONNECT", src.contains("RequestPermission()") && src.contains("BluetoothPermission"))
    }

    @Test fun viewModelsNeverPollOrBlock() {
        val vms = File(main, "phone/ui").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f -> f.readText().contains(": ViewModel()") }.toList()
        assertTrue("expected ViewModels under phone/ui", vms.size >= 9)
        for (f in vms) {
            val src = f.readText()
            assertFalse("${f.name}: runBlocking", src.contains("runBlocking"))
            assertFalse("${f.name}: while (true)", src.contains("while (true)"))
            // LegacyConfig's 600 ms debounce is the only allowed delay in a ViewModel file (Phase 2 ruling).
            if (f.name != "LegacyConfig.kt") assertFalse("${f.name}: delay(", src.contains("delay("))
        }
    }

    @Test fun newPhoneUiNeverUsesTheLegacyFacades() {
        File(main, "phone/ui").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val src = f.readText()
            for (facade in listOf("MemoryRecordStore", "FrameStreamStore", "SummaryTraceStore")) assertFalse("${f.name}: $facade", src.contains(facade))
        }
    }

    @Test fun productionDepsReadTheHubStatus() {
        val src = File(main, "phone/ui/shell/PhoneDeps.kt").readText()
        assertTrue(src.contains("HubGraph.glassesStatus("))
        assertFalse(src.contains("FakeGlassesStatusSource"))
    }

    @Test fun legacyFacadesAreDeletedEverywhere() {
        val roots = listOf(File("src/main"), File("../services/memory/src/main"), File("../services/orchestration/src/main"))
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
            .filter { f -> listOf("MemoryRecordStore", "FrameStreamStore", "SummaryTraceStore").any { f.readText().contains(it) } }
            .map { it.path }
        assertTrue("facade references left: $hits", hits.isEmpty())
    }
}
