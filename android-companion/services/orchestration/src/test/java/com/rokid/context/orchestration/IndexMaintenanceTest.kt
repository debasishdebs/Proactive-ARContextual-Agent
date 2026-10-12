package com.rokid.context.orchestration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.RoleBindingRepository
import com.rokid.context.ai.embed.Canaries
import com.rokid.context.ai.embed.Embedders
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.EndpointEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.retrieval.VectorMath
import com.rokid.context.orchestration.IndexMaintenance.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IndexMaintenanceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val memories = MemoryRepository(db.memoryDao())
    private var now = 1_000_000L

    /**
     * Model "fp" with [dim]; [drift] turns every vector sideways (a model silently replaced behind the same name);
     * [tilt] rotates every vector by that many radians (cosine to the original = cos(tilt)); [docDim] makes document
     * vectors come back with another size than the bound dimension.
     */
    private val fake = object : IndexEmbedders {
        var fp = "A"; var dim = 2; var drift = false; var bound = true; var calls = 0; var tilt = 0.0; var docDim: Int? = null
        fun embedder(fingerprint: String = fp, d: Int = dim) = object : Embedder {
            override val fingerprint = fingerprint
            override suspend fun embedDocuments(texts: List<String>) = texts.map { t ->
                calls++
                val v = FloatArray(docDim ?: d) { i -> if (drift) (if (i == 1) 1f else 0f) else if (i == 0) 1f else (t.length % 5) / 10f }
                if (tilt != 0.0 && v.size >= 2) { val x = v[0]; val y = v[1]; v[0] = (x * cos(tilt) - y * sin(tilt)).toFloat(); v[1] = (x * sin(tilt) + y * cos(tilt)).toFloat() }
                v
            }
            override suspend fun embedQuery(text: String) = embedDocuments(listOf(text)).single()
        }
        override suspend fun bound() = if (!bound) null else BoundEmbedder(embedder(), "{\"fp\":\"$fp\"}", dim)
        override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder = embedder(space.fingerprint, space.dim)
    }
    private val clock = object : IndexClock { var at = 0L; override fun lastCanaryAt() = at; override fun markCanary(at: Long) { this.at = at } }
    private fun maintenance(maxPerRun: Int = 256) = IndexMaintenance(spaces, fake, clock, { now }, batch = 2, maxPerRun = maxPerRun)
    @After fun close() = db.close()

    private suspend fun mem(id: String) = memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))

    @Test fun noBindingIsIdle() = runBlocking {
        fake.bound = false
        assertEquals(Outcome.IDLE, maintenance().run())
        assertNull(spaces.current())
    }

    @Test fun firstRunBuildsAndActivatesAtFullCoverage() = runBlocking {
        mem("m1"); mem("m2"); mem("m3")
        assertEquals(Outcome.DONE, maintenance().run())
        val active = spaces.queryable()!!
        assertEquals("A", active.fingerprint)
        assertEquals(3, spaces.coverage(active.id).have)
    }

    @Test fun aBigBacklogContinuesAcrossRunsAndOnlySwapsWhenComplete() = runBlocking {
        repeat(5) { mem("m$it") }
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 2).run())
        assertNull(spaces.queryable()) // not yet complete: nothing to read, semantic falls back to lexical
        while (maintenance(maxPerRun = 2).run() == Outcome.MORE) Unit
        assertEquals(5, spaces.coverage(spaces.queryable()!!.id).have)
    }

    @Test fun bindingChangeBuildsBesideTheOldSpaceAndNewMemoriesGoIntoBoth() = runBlocking {
        mem("m1"); maintenance().run()
        val old = spaces.queryable()!!
        repeat(4) { mem("x$it") }
        fake.fp = "B"
        assertEquals(Outcome.MORE, maintenance(maxPerRun = 6).run()) // 4 into the old space, 2 into the new one
        assertEquals(old.id, spaces.queryable()!!.id)
        assertEquals(5, spaces.coverage(old.id).have)
        mem("late")
        while (maintenance(maxPerRun = 6).run() == Outcome.MORE) Unit
        val active = spaces.queryable()!!
        assertEquals("B", active.fingerprint); assertEquals(6, spaces.coverage(active.id).have)
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(old.id)!!.state)
    }

    @Test fun canaryDriftMarksTheSpaceStaleAndRebuildsAtMostDaily() = runBlocking {
        mem("m1"); maintenance().run()
        clock.at = now // F4: the last canary check is "now", so the 24 h guard is what holds the next check back
        val first = spaces.queryable()!!
        fake.drift = true
        now += 3_600_000 // within 24 h of the last canary check: not checked again
        val before = fake.calls
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals("no canary re-embedding within 24 h", before, fake.calls)
        assertEquals(first.id, spaces.queryable()!!.id)
        assertEquals(clock.at, now - 3_600_000)
        now += 25 * 3_600_000L
        assertEquals(Outcome.DONE, maintenance().run())
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id); assertEquals("A", rebuilt.fingerprint)
        assertEquals(now, clock.at)
    }

    @Test fun canariesUseTheSpecThreshold() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        fake.tilt = acos(0.998) // still the same model: cosine 0.998 >= 0.995
        now += 25 * 3_600_000L
        maintenance().run()
        assertEquals(first.id, spaces.queryable()!!.id)
        fake.tilt = acos(0.99) // drifted: cosine 0.99 < 0.995 against the stored canaries
        now += 25 * 3_600_000L
        maintenance().run()
        val rebuilt = spaces.queryable()!!
        assertNotEquals(first.id, rebuilt.id)
        assertEquals(SpaceState.RETIRED, db.embeddingDao().space(first.id)?.state ?: SpaceState.RETIRED)
    }

    @Test fun canariesAreStoredAtCreation() = runBlocking {
        mem("m1"); maintenance().run()
        val stored = Canaries.decode(spaces.queryable()!!.canaryJson)
        val expected = fake.embedder().embedDocuments(Canaries.TEXTS)
        assertEquals(3, stored.size)
        stored.zip(expected).forEach { (a, b) -> assertArrayEquals(b, a, 0f) }
    }

    @Test fun driftWhileBuildingIsCaughtBeforeTheSwap() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.drift = true // the model changed behind its name in the middle of the build
        while (maintenance(maxPerRun = 2).run() == Outcome.MORE) Unit
        assertNull("a drifted build is never swapped in", db.embeddingDao().space(partial.id))
        val active = spaces.queryable()!!
        assertTrue(Canaries.decode(active.canaryJson).all { it[1] == 1f }) // rebuilt with the new model's canaries
    }

    @Test fun dimensionChangeRestartsTheBuild() = runBlocking {
        repeat(4) { mem("m$it") }
        maintenance(maxPerRun = 2).run()
        val partial = spaces.building()!!
        fake.dim = 3; fake.fp = "A-1024"
        while (maintenance(maxPerRun = 2).run() == Outcome.MORE) Unit
        assertNull(db.embeddingDao().space(partial.id))
        assertEquals(3, spaces.queryable()!!.dim)
    }

    @Test fun aWrongSizedVectorIsAConfigFailureThatWritesNothing() = runBlocking {
        repeat(3) { mem("m$it") }
        fake.docDim = 3 // the bound dimension is 2, but documents come back with 3
        repeat(2) {
            try { maintenance().run(); fail("expected IndexConfigException") } catch (_: IndexConfigException) { }
        }
        val building = spaces.building()!!
        assertEquals(0, spaces.coverage(building.id).have)
        assertNull(spaces.queryable())
    }

    @Test fun aWrongSizedVectorForTheOldSpaceIsAlsoAConfigFailure() = runBlocking {
        mem("m1"); maintenance().run()
        mem("m2")
        fake.docDim = 3
        try { maintenance().run(); fail("expected IndexConfigException") } catch (_: IndexConfigException) { }
    }

    @Test fun rebuildForcesANewSpaceForTheSameModel() = runBlocking {
        mem("m1"); maintenance().run()
        val first = spaces.queryable()!!
        assertEquals(Outcome.DONE, maintenance().run(rebuild = true))
        assertNotEquals(first.id, spaces.queryable()!!.id)
        assertTrue(fake.calls > 0)
    }

    /** Carried from Task 9: the stored canaries are embedded through the space's own embedder, so its document prefix is applied. */
    @Test fun canariesGoThroughTheRealEmbedderWithTheDocumentPrefix() = runBlocking {
        val inputs = mutableListOf<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/tags" -> MockResponse().setBody("""{"models":[{"name":"nomic-embed-text:latest","digest":"sha256:abc"}]}""")
                    "/api/embed" -> {
                        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["input"]!!.jsonArray.map { it.jsonPrimitive.content }
                        synchronized(inputs) { inputs += sent }
                        // The vector depends on the exact text sent, so a missing prefix would change the stored canaries.
                        MockResponse().setBody("""{"embeddings":[${sent.joinToString(",") { "[3,${it.length % 7 + 1},0]" }}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
            val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
            db.endpointDao().upsert(EndpointEntity("ol", "Ollama", "OLLAMA", server.url("/").toString().trimEnd('/'), null,
                "nomic-embed-text", EndpointRepository.keyRef("ol"), "EMBEDDINGS", 1))
            bindings.set(RoleBinding(Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
            mem("m1")
            assertEquals(Outcome.DONE, IndexMaintenance(spaces, AiIndexEmbedders(Embedders(endpoints, bindings)), clock, { now }).run())
            val space = spaces.queryable()!!
            Canaries.TEXTS.forEach { t -> assertTrue("canary sent with the document prefix", "search_document: $t" in inputs) }
            val expected = Canaries.TEXTS.map { t -> VectorMath.l2(floatArrayOf(3f, (("search_document: $t").length % 7 + 1).toFloat(), 0f)) }
            Canaries.decode(space.canaryJson).zip(expected).forEach { (a, b) -> assertArrayEquals(b, a, 1e-6f) }
            assertEquals(3, Canaries.decode(space.canaryJson).size)
        } finally { server.shutdown() }
    }
}
