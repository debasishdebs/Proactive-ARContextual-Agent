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
        val deps = SettingsDeps(lazyAi = lazy<com.rokid.context.ai.AiGraph> { built++; error("not a real graph") }, hud = FakeHudSettings(), reauthorize = {}, onEmbedBindingChanged = {},
            lazyTestInputs = lazy<com.rokid.context.orchestration.TestBedInputs> { inputs++; error("not real inputs") },
            lazyRetrieval = lazy<com.rokid.context.memory.retrieval.RetrievalSettingsStore> { retrieval++; error("not a real store") },
            lazySpaces = lazy<com.rokid.context.memory.EmbeddingSpaces> { error("not real spaces") },
            rebuildIndex = {})
        assertEquals(0, built); assertEquals(0, inputs); assertEquals(0, retrieval)
        runCatching { deps.ai }
        assertEquals(1, built)
        runCatching { deps.testInputs }
        assertEquals(1, inputs)
        runCatching { deps.retrieval }
        assertEquals(1, retrieval)
    }

    @Test fun createDefersAiGraphGet() {
        val src = File("src/main/java/com/rokid/context/phone/ui/settings/SettingsDeps.kt").readText()
        assertTrue("SettingsDeps.create must wrap AiGraph.get in lazy", src.contains("lazyAi = lazy { AiGraph.get(app) }"))
        assertTrue("SettingsDeps.create must wrap the test-bed inputs (AnswerService.forApp reaches AiGraph) in lazy",
            src.contains("lazyTestInputs = lazy { TestBedInputs(MemoryGraph.get(app), AnswerService.forApp(app)) }"))
    }
}
