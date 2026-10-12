package com.rokid.context.memory.retrieval

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetrievalSettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("retrieval_test", Context.MODE_PRIVATE)

    @Test fun defaultsPersistAndClamp() {
        val store = RetrievalSettingsStore(prefs)
        assertEquals(RetrievalSettings(), store.settings.value)
        store.update { it.copy(mode = RetrievalMode.SEMANTIC, fusion = FusionStrategy.Rrf(500), topK = 99, halfLifeDays = 0.0, minConfidence = 2.0) }
        val again = RetrievalSettingsStore(prefs).settings.value
        assertEquals(RetrievalSettings(RetrievalMode.SEMANTIC, FusionStrategy.Rrf(200), 20, true, 0.25, 1.0), again)
    }

    @Test fun eachStrategyKeepsItsOwnConfig() {
        val store = RetrievalSettingsStore(prefs)
        store.update { it.copy(fusion = FusionStrategy.WeightedScore(0.3)) }
        store.update { it.copy(fusion = FusionStrategy.Rrf(42)) }
        assertEquals(FusionStrategy.WeightedScore(0.3), store.weightedConfig()) // doubles are stored exactly
        store.update { it.copy(fusion = store.weightedConfig()) }
        assertEquals(FusionStrategy.WeightedScore(0.3), RetrievalSettingsStore(prefs).settings.value.fusion)
        assertEquals(FusionStrategy.Rrf(42), store.rrfConfig())
    }
}
