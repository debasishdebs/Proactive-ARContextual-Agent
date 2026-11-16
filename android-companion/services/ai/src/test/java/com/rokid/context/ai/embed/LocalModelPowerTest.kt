package com.rokid.context.ai.embed

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.BruteForceVectorIndex
import com.rokid.context.memory.retrieval.LexicalRetriever
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.SemanticRetriever
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Gaps G6 (MVP spec 6: "Local model allowed only when charging OR battery >70%") for on-device embeddings.
 * Ruling: the spec gives no rule for a user's query embed (it predates on-device embeddings; "manual readContext
 * bypasses schedule"), so one short query (<= [LocalModelPower.QUERY_MAX_CHARS]) is allowed at any battery; bulk
 * (document) embedding and longer queries are gated.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalModelPowerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val low = PowerState(batteryPct = 40, charging = false)
    private var docCalls = 0
    private var queryCalls = 0
    private val inner = object : Embedder {
        override val fingerprint = "fp"
        override suspend fun embedDocuments(texts: List<String>): List<FloatArray> { docCalls++; return texts.map { floatArrayOf(1f, 0f) } }
        override suspend fun embedQuery(text: String): FloatArray { queryCalls++; return floatArrayOf(1f, 0f) }
    }
    private val longQuery = "where did I leave the milk carton " + "and the rest of the shopping ".repeat(30)
    private val db = DatabaseFactory.inMemory(ctx)
    @After fun close() { MemoryGraph.resetForTests(null); db.close(); LocalModelPower.minBatteryPct = { LocalModelPower.DEFAULT_MIN_BATTERY_PCT } }

    @Test fun theBatteryLevelIsASettingDefaultFifty() {
        assertEquals(50, LocalModelPower.MIN_BATTERY_PCT)
        assertTrue(LocalModelPower.bulkAllowed(PowerState(51, false)))
        assertFalse(LocalModelPower.bulkAllowed(PowerState(50, false)))
        LocalModelPower.minBatteryPct = { 30 }
        assertTrue(LocalModelPower.bulkAllowed(PowerState(31, false)))
        assertFalse(LocalModelPower.bulkAllowed(PowerState(30, false)))
        LocalModelPower.minBatteryPct = { error("prefs") }
        assertEquals("a failing provider keeps the default", 50, LocalModelPower.MIN_BATTERY_PCT)
    }

    @Test fun theRuleIsChargingOrAboveSeventy() {
        LocalModelPower.minBatteryPct = { 70 }
        assertTrue(LocalModelPower.bulkAllowed(PowerState(71, false)))
        assertFalse(LocalModelPower.bulkAllowed(PowerState(70, false)))
        assertTrue(LocalModelPower.bulkAllowed(PowerState(5, true)))
        assertFalse("unknown battery, not charging: gated", LocalModelPower.bulkAllowed(PowerState(-1, false)))
    }

    @Test fun queryEmbedPolicyFollowsRuling() = runBlocking {
        val gated = PowerGatedEmbedder(inner) { low }
        assertEquals(2, gated.embedQuery("where are my keys").size) // one short query: allowed at low battery
        assertEquals(1, queryCalls)
        try { gated.embedQuery(longQuery); fail("a long query is gated") } catch (e: LocalModelPausedException) { assertEquals(null, e.message) }
        try { gated.embedDocuments(listOf("a", "b")); fail("bulk is gated") } catch (e: LocalModelPausedException) { /* expected */ }
        assertEquals(0, docCalls); assertEquals(1, queryCalls)
        val charging = PowerGatedEmbedder(inner) { PowerState(10, true) }
        charging.embedDocuments(listOf("a")); charging.embedQuery(longQuery)
        assertEquals(1, docCalls); assertEquals(2, queryCalls)
    }

    @Test fun onlyOnDeviceSpacesAreGated() = runBlocking {
        MemoryGraph.resetForTests(db); val memory = MemoryGraph.get(ctx)
        val ai = AiGraph(memory, InMemoryKeyVault(), InMemorySeedState(), power = { low })
        for ((id, kind, url) in listOf(Triple("loc", EndpointKind.LLAMACPP_LOCAL, "/data/local/tmp/embed.gguf"), Triple("oll", EndpointKind.OLLAMA, "http://127.0.0.1:11434"))) {
            ai.endpoints.save(Endpoint(id, id, kind, url, null, listOf("m"), setOf(Capability.EMBEDDINGS), 1), null).getOrThrow()
            val spec = EmbeddingSpec(id, kind, url, null, "m", null, null, 2, true, "", "", 2_000)
            val space = memory.spaces.startBuilding(spec.fingerprint, spec.configJson(), 2, "[]", 1)
            val e = ai.embedders.forSpace(space)
            assertEquals(id, kind == EndpointKind.LLAMACPP_LOCAL, e is PowerGatedEmbedder)
        }
    }

    @Test fun gatedSearchFallsBackToLexical() = runBlocking {
        val spaces = EmbeddingSpaces(db.embeddingDao())
        MemoryRepository(db.memoryDao()).add(MemoryEntity("milk", null, "milk carton", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1L, false, ""))
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1); spaces.put("milk", s.id, floatArrayOf(1f, 0f)); assertTrue(spaces.swapIfComplete(s.id))
        val provider = SpaceEmbedderProvider { PowerGatedEmbedder(inner) { low } }
        val retriever = MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao()), provider), db.memoryViewDao())
        val r = retriever.retrieve(longQuery, RetrievalSettings(mode = RetrievalMode.HYBRID), 2L)
        assertEquals(RetrievalMode.LEXICAL, r.modeUsed); assertTrue(r.semanticUnavailable)
        assertEquals(listOf("milk"), r.hits.map { it.memory.id })
        assertEquals("the model was never asked", 0, queryCalls)
    }

    @Test fun gatedForwardsImagesWithoutBulkGate() = runBlocking {
        var imageCalls = 0
        val imaging = object : Embedder by inner {
            override val modalities = setOf(com.rokid.context.memory.Modality.TEXT, com.rokid.context.memory.Modality.IMAGE)
            override val imageDims: Int? = 768
            override suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> { imageCalls++; return jpegs.map { floatArrayOf(1f) } }
        }
        val gated = PowerGatedEmbedder(imaging) { low }
        assertEquals(1, gated.embedImages(listOf(byteArrayOf(1))).size) // live embedding at low battery (Ruling 18)
        assertEquals(1, imageCalls)
        assertEquals(768, gated.imageDims)
        try { gated.embedDocuments(listOf("a")); fail("text bulk stays gated") } catch (e: LocalModelPausedException) { /* expected */ }
    }

    @Test fun gatedForwardsModalities() {
        val imaging = object : Embedder by inner { override val modalities = setOf(com.rokid.context.memory.Modality.TEXT, com.rokid.context.memory.Modality.IMAGE) }
        assertEquals(imaging.modalities, PowerGatedEmbedder(imaging) { low }.modalities)
        assertEquals(setOf(com.rokid.context.memory.Modality.TEXT), PowerGatedEmbedder(inner) { low }.modalities)
    }

    @Test fun powerIsReadFromTheBatteryService() {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 55); shadowOf(bm).setIsCharging(false)
        assertEquals(PowerState(55, false), LocalModelPower.read(ctx))
        shadowOf(bm).setIsCharging(true)
        assertTrue(LocalModelPower.read(ctx).charging)
    }
}
