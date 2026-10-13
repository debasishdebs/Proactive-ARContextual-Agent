package com.rokid.context.phone.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Task 16 review carry: building SettingsDeps (MainActivity.onCreate, on Main) must not build the AI graph and its prefs-backed settings. */
class SettingsDepsTest {
    @Test fun theAiGraphIsBuiltOnFirstUseOnly() {
        var built = 0
        val deps = SettingsDeps(lazyAi = lazy<com.rokid.context.ai.AiGraph> { built++; error("not a real graph") }, hud = FakeHudSettings(), reauthorize = {}, onEmbedBindingChanged = {})
        assertEquals(0, built)
        runCatching { deps.ai }
        assertEquals(1, built)
    }

    @Test fun createDefersAiGraphGet() {
        val src = File("src/main/java/com/rokid/context/phone/ui/settings/SettingsDeps.kt").readText()
        assertTrue("SettingsDeps.create must wrap AiGraph.get in lazy", src.contains("lazyAi = lazy { AiGraph.get(app) }"))
    }
}
