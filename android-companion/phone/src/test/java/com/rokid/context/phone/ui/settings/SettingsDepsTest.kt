package com.rokid.context.phone.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Task 16 review carry: building SettingsDeps (MainActivity.onCreate, on Main) must not build the AI graph and its prefs-backed settings. */
class SettingsDepsTest {
    @Test fun theAiGraphIsBuiltOnFirstUseOnly() {
        var built = 0
        var inputs = 0
        var retrieval = 0
        var memorySettings = 0
        var actions = 0
        val deps = SettingsDeps(lazyAi = lazy<com.rokid.context.ai.AiGraph> { built++; error("not a real graph") }, hud = FakeHudSettings(), reauthorize = {}, onEmbedBindingChanged = {},
            lazyTestInputs = lazy<com.rokid.context.orchestration.TestBedInputs> { inputs++; error("not real inputs") },
            lazyRetrieval = lazy<com.rokid.context.memory.retrieval.RetrievalSettingsStore> { retrieval++; error("not a real store") },
            lazySpaces = lazy<com.rokid.context.memory.EmbeddingSpaces> { error("not real spaces") },
            rebuildIndex = {},
            lazyMemorySettings = lazy<com.rokid.context.memory.MemorySettingsStore> { memorySettings++; error("not a real store") },
            lazyWatchList = lazy<com.rokid.context.work.frames.scene.WatchListStore> { error("not a real store") },
            encryption = object : com.rokid.context.phone.ui.settings.memory.EncryptionControl {
                override fun status() = com.rokid.context.memory.DbEncryption.Status(false, null, false); override fun request(encrypted: Boolean) = Unit
            },
            usedBytes = { 0L }, wipe = {}, export = { File("x") }, share = {}, logs = { emptyList() }, exportDiagnostics = { File("d") },
            lazyActions = lazy<com.rokid.context.connectors.actions.ActionsGraphApi> { actions++; error("not a real graph") })
        assertEquals(0, built); assertEquals(0, inputs); assertEquals(0, retrieval); assertEquals(0, memorySettings)
        runCatching { deps.ai }
        assertEquals(1, built)
        runCatching { deps.testInputs }
        assertEquals(1, inputs)
        runCatching { deps.retrieval }
        assertEquals(1, retrieval)
        runCatching { deps.memorySettings } // Task 20: reads prefs, so built on first use too
        assertEquals(1, memorySettings)
        assertEquals(0, actions)
        runCatching { deps.actions } // Phase 7 integration carry 5
        assertEquals(1, actions)
    }

    @Test fun createDefersAiGraphGet() {
        val src = File("src/main/java/com/rokid/context/phone/ui/settings/SettingsDeps.kt").readText()
        assertTrue("SettingsDeps.create must wrap AiGraph.get in lazy", src.contains("lazyAi = lazy { AiGraph.get(app) }"))
        assertTrue("SettingsDeps.create must wrap the test-bed inputs (AnswerService.forApp reaches AiGraph) in lazy",
            Regex("""lazyTestInputs = lazy \{\s+val mem = MemoryGraph.get\(app\)\s+TestBedInputs\(mem, AnswerService.forApp\(app\)""").containsMatchIn(src))
        // T19 fix round 1: the Rebuild (button or banner tap, both on Main) commits prefs; it runs on IO.
        assertTrue("rebuildIndex runs on IO", src.contains("rebuildIndex = { scope.launch(Dispatchers.IO) { com.rokid.context.orchestration.EmbeddingWorker.enqueue(app, rebuild = true) } }"))
    }
}
