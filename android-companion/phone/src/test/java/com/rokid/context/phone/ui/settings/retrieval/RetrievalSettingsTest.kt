package com.rokid.context.phone.ui.settings.retrieval

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.FusionStrategy
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettingsStore
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RetrievalSettingsTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private lateinit var store: RetrievalSettingsStore
    private lateinit var vm: RetrievalViewModel
    private var rebuilds = 0

    @Before fun setUp() = runBlocking {
        TestGraph.fresh(ctx)
        ai = TestSettings.ai(ctx)
        store = RetrievalSettingsStore(ctx.getSharedPreferences("retrieval_vm_test", Context.MODE_PRIVATE))
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 10, "Milk carton and more milk in the fridge"))
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("m2", "short_term", 0.3, 20, "Milk spilled on the floor"))
        vm = RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, { rebuilds++ }, now = { 1_000 }, work = main.dispatcher)
    }

    @After fun tearDown() { vm.cancelScope(); TestGraph.close(ctx) }

    @Test fun controlsWriteTheStoreAndEachStrategyKeepsItsConfig() = runBlocking {
        vm.setMode(RetrievalMode.LEXICAL); vm.setTopK(3); vm.setMinConfidence(0.5f); vm.setHalfLife(7f); vm.setRecency(false)
        vm.setFusion(1); vm.setRrfK("42"); vm.setFusion(0); vm.setWeight(0.25f); vm.setFusion(1)
        val s = store.settings.value
        assertEquals(RetrievalMode.LEXICAL, s.mode); assertEquals(3, s.topK); assertEquals(0.5, s.minConfidence, 1e-6)
        assertEquals(7.0, s.halfLifeDays, 1e-6); assertEquals(false, s.recencyBoost)
        assertEquals(FusionStrategy.Rrf(42), s.fusion)
        assertEquals(0.25, store.weightedConfig().semanticWeight, 1e-6)
        vm.setRrfK("abc"); assertEquals("k is a whole number 1–200", vm.state.value.rrfError)
    }

    @Test fun tryItRanksHitsWithScoresAndShowsTheFallback() = runBlocking {
        vm.onQuery("milk"); vm.tryIt()
        val s = withTimeout(5_000) { vm.state.first { it.results.isNotEmpty() && !it.searching } }
        assertEquals(listOf("Milk carton and more milk in the fridge", "Milk spilled on the floor"), s.results.map { it.text }) // BM25: two hits beat one
        assertTrue(s.results.first().scores, s.results.first().scores.contains("lexical"))
        assertEquals("Semantic search is off: " + com.rokid.context.memory.retrieval.SemanticOff.NO_INDEX.text + " — showing lexical results", s.fallbackNotice) // default mode is Hybrid, no index yet
        vm.setMinConfidence(0.5f); vm.tryIt()
        assertEquals(1, withTimeout(5_000) { vm.state.first { it.results.size == 1 && !it.searching } }.results.size)
    }

    @Test fun statsSayWhyThereIsNoSemanticIndexAndRebuildEnqueues() = runBlocking {
        val s = withTimeout(5_000) { vm.state.first { it.embedSummary.isNotEmpty() && it.indexSummary.isNotEmpty() } }
        assertEquals("Not set — semantic search is off", s.embedSummary)
        assertEquals("No index yet", s.indexSummary)
        vm.rebuild(); assertEquals(1, rebuilds)
    }

    @Test fun fusionConfigShowsOnlyForHybrid() {
        compose.setContent { PcTheme { RetrievalSettingsScreen(RetrievalUiState(mode = RetrievalMode.HYBRID, fusionIndex = 0, weight = 0.6f), onBack = {}, onMode = {}, onFusion = {}, onWeight = {},
            onRrfK = {}, onTopK = {}, onRecency = {}, onHalfLife = {}, onMinConfidence = {}, onQuery = {}, onTryIt = {}, onRebuild = {}, onOpenRoles = {}) } }
        compose.onNodeWithTag("fusion_weight").assertExists()
        // Lazy list: later cards are composed only once scrolled to.
        compose.onNodeWithTag("retrieval_settings").performScrollToNode(hasTestTag("index_rebuild"))
        compose.onNodeWithTag("index_rebuild").assertExists()
        compose.onNodeWithTag("retrieval_settings").performScrollToNode(hasTestTag("try_it"))
        compose.onNodeWithTag("try_it").assertExists()
    }

    @Test fun everyControlIsClampedAndABadRrfKNeverReachesTheStore() {
        vm.setTopK(50); assertEquals(20, store.settings.value.topK)
        vm.setTopK(0); assertEquals(1, store.settings.value.topK)
        vm.setWeight(1.7f); assertEquals(1.0, store.weightedConfig().semanticWeight, 1e-6)
        vm.setWeight(Float.NaN); assertEquals(0.6, store.weightedConfig().semanticWeight, 1e-6)
        vm.setHalfLife(100f); assertEquals(30.0, store.settings.value.halfLifeDays, 1e-6)
        vm.setMinConfidence(2f); assertEquals(1.0, store.settings.value.minConfidence, 1e-6)
        vm.setFusion(1); vm.setRrfK("500"); assertEquals("k is a whole number 1–200", vm.state.value.rrfError)
        vm.setRrfK("0"); assertEquals(FusionStrategy.Rrf(60), store.settings.value.fusion)
        vm.setFusion(7); assertEquals(FusionStrategy.WeightedScore(0.6), store.settings.value.fusion)
    }

    @Test fun tryItRunsOffMain() = runBlocking {
        val main = Thread.currentThread()
        var ranOn: Thread? = null
        val offMain = RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, {}, now = { ranOn = Thread.currentThread(); 1_000 })
        try {
            offMain.onQuery("milk"); offMain.tryIt()
            withTimeout(30_000) { offMain.state.first { it.results.isNotEmpty() && !it.searching } }
            assertTrue("ran on ${ranOn?.name}", ranOn != null && ranOn !== main)
        } finally { offMain.cancelScope() }
    }

    @Test fun aFailedSearchShowsFixedTextNeverTheException() = runBlocking {
        val failing = RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, {}, now = { throw IllegalStateException("provider said sk-secret-123") }, work = main.dispatcher)
        try {
            failing.onQuery("milk"); failing.tryIt()
            val s = withTimeout(5_000) { failing.state.first { it.tryError != null && !it.searching } }
            assertEquals("Search failed", s.tryError)
            assertTrue(s.toString(), !s.toString().contains("sk-secret") && !s.toString().contains("IllegalState"))
        } finally { failing.cancelScope() }
    }

    @Test fun aTryItResultNeverClearsTheRrfErrorAndNoMatchesKeepsKInSync() = runBlocking {
        vm.setFusion(1); vm.setRrfK("abc")
        vm.onQuery("milk"); vm.tryIt()
        val s = withTimeout(5_000) { vm.state.first { it.results.isNotEmpty() && !it.searching } }
        assertEquals("k is a whole number 1–200", s.rrfError); assertEquals(null, s.tryError)
        vm.setRrfK("42"); assertEquals(null, vm.state.value.rrfError)
        vm.onQuery("zebra"); vm.tryIt()
        assertEquals("No matches", withTimeout(5_000) { vm.state.first { it.tryError != null && !it.searching } }.tryError)
        store.update { it.copy(fusion = FusionStrategy.Rrf(77)) }
        assertEquals("77", vm.state.value.rrfK)
    }

    @Test fun rebuildRunsOffMain() = runBlocking {
        val main = Thread.currentThread()
        val ranOn = kotlinx.coroutines.CompletableDeferred<Thread>()
        val offMain = RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, { ranOn.complete(Thread.currentThread()) }, now = { 1_000 })
        try {
            offMain.rebuild()
            val t = withTimeout(30_000) { ranOn.await() }
            assertTrue("ran on ${t.name}", t !== main)
        } finally { offMain.cancelScope() }
    }

    /** Phase 5 T19 carry: leaving the screen cancels Try it; a search that finishes afterwards writes nothing. */
    @Test fun tryItIsCancelledWhenTheScreenGoesAway() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        // Preflight F9: fires once now() has handed back its value, so the assertions run after the blocked search has resumed.
        val returned = java.util.concurrent.CountDownLatch(1)
        val leaving = RetrievalViewModel(store, ai.retriever, MemoryGraph.get(ctx).spaces, ai, {}, now = {
            entered.complete(Unit); release.await(10, java.util.concurrent.TimeUnit.SECONDS); returned.countDown(); 1_000
        })
        leaving.onQuery("milk"); leaving.tryIt()
        withTimeout(30_000) { entered.await() }
        kotlin.concurrent.thread { Thread.sleep(200); release.countDown() } // the blocked search ends after the cancel
        leaving.cancelScope()
        assertTrue("the search resumed", returned.await(30, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(500) // let a (wrongly) uncancelled search finish retrieving and write its results
        assertTrue(leaving.state.value.results.isEmpty())
        assertTrue(leaving.state.value.tryError == null)
    }
}
