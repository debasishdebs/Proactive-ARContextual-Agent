package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ExportDao
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryExportTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun exportHasMemoriesAndAsksInTheCacheExportsFolder() = runBlocking {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "दूध फ्रिज में है"))
        g.asks.add(AskEntity("a1", 2, "milk?", "In the fridge", "[\"m1\"]", null, "phone_typed", false))
        val f = MemoryExport.write(ctx, nowMs = 1_760_000_000_000)
        assertEquals("exports", f.parentFile!!.name); assertTrue(f.parentFile!!.parentFile == ctx.cacheDir)
        val json = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals("दूध फ्रिज में है", json["memories"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("In the fridge", json["asks"]!!.jsonArray.single().jsonObject["answer"]!!.jsonPrimitive.content)
        assertFalse(f.readText().contains("apiKey") || f.readText().contains("endpoint"))
    }

    /** F22: a new export replaces older memory exports only; other files in cache/exports (diagnostics, Task 21) stay. */
    @Test fun aNewExportRemovesOnlyOlderMemoryExports() = runBlocking<Unit> {
        val dir = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }
        val diagnostics = java.io.File(dir, "proactive-context-diagnostics-1.txt").apply { writeText("d") }
        val old = MemoryExport.write(ctx, nowMs = 1)
        val new = MemoryExport.write(ctx, nowMs = 2)
        assertFalse(old.exists()); assertTrue(new.exists()); assertTrue(diagnostics.exists())
        dir.deleteRecursively()
    }

    /** Review M3: a forgotten (INVALIDATED) memory's text is never exported. */
    @Test fun invalidatedMemoriesAreNeverExported() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("keep", "short_term", 0.9, 1, "Keys on the shelf"))
        g.memories.add(LegacyImporter.toMemory("gone", "short_term", 0.9, 2, "Forgotten secret").copy(reviewState = com.rokid.context.memory.db.ReviewState.INVALIDATED))
        val f = MemoryExport.write(ctx, nowMs = 3)
        val ids = Json.parseToJsonElement(f.readText()).jsonObject["memories"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("keep"), ids)
        assertFalse(f.readText().contains("Forgotten secret"))
        f.delete()
    }

    private fun bulkAsks(n: Int) {
        val w = MemoryGraph.get(ctx).db.openHelper.writableDatabase
        w.beginTransaction()
        try {
            val st = w.compileStatement("INSERT INTO ask (id, ts, question, answer, sourcesJson, rating, source, redacted) VALUES (?, ?, 'q', 'a', '[]', NULL, 'phone_typed', 0)")
            repeat(n) { st.clearBindings(); st.bindString(1, "a$it"); st.bindLong(2, it.toLong()); st.executeInsert() }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    /** B1: every ask is exported; the old 10 k cap is gone. */
    @Test fun exportsEveryAskBeyondTenThousand() = runBlocking<Unit> {
        bulkAsks(12_000)
        val f = MemoryExport.write(ctx, nowMs = 10)
        val asks = Json.parseToJsonElement(f.readText()).jsonObject["asks"]!!.jsonArray
        assertEquals(12_000, asks.size)
        assertEquals(12_000, asks.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet().size)
        f.delete()
    }

    private class SpyDao(private val real: ExportDao, private val failOnMemoryCall: Int = -1) : ExportDao {
        val limits = mutableListOf<Int>(); var memoryCalls = 0; var askCalls = 0
        override suspend fun memoryPage(after: Long, limit: Int): List<com.rokid.context.memory.db.MemoryExportRow> {
            limits += limit; memoryCalls++
            if (memoryCalls == failOnMemoryCall) throw IllegalStateException("disk")
            return real.memoryPage(after, limit)
        }
        override suspend fun askPage(after: Long, limit: Int): List<com.rokid.context.memory.db.AskExportRow> {
            limits += limit; askCalls++
            return real.askPage(after, limit)
        }
    }

    /** B1: the export reads the library in 500-row pages through ExportDao, never the whole table at once. */
    @Test fun exportReadsInPagesNotAllAtOnce() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        repeat(1_200) { g.memories.add(LegacyImporter.toMemory("m$it", "short_term", 0.9, it.toLong(), "note $it")) }
        bulkAsks(700)
        val spy = SpyDao(g.db.exportDao())
        val f = MemoryExport.write(ctx, spy, nowMs = 11)
        assertEquals(3, spy.memoryCalls); assertEquals(2, spy.askCalls)
        assertTrue(spy.limits.toString(), spy.limits.all { it == 500 })
        val json = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals(1_200, json["memories"]!!.jsonArray.size); assertEquals(700, json["asks"]!!.jsonArray.size)
        f.delete()
    }

    /** B1: same JSON shape and key names as before the streaming rewrite. */
    @Test fun jsonShapeIsUnchanged() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "one"))
        g.db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("f2", 1, "/f2.jpg", "rokid_glasses")) // a live source frame (an orphan is not exported)
        g.memories.add(LegacyImporter.toMemory("m2", "short_term", 0.5, 2, "two").copy(frameId = "f2"))
        g.asks.add(AskEntity("a1", 2, "milk?", "In the fridge", "[\"m1\"]", null, "phone_typed", false))
        val f = MemoryExport.write(ctx, nowMs = 12)
        val json = Json.parseToJsonElement(f.readText()).jsonObject
        assertEquals(setOf("exportedAt", "memories", "asks"), json.keys)
        assertEquals(12L, json["exportedAt"]!!.jsonPrimitive.content.toLong())
        val mems = json["memories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, mems.size)
        mems.forEach { assertEquals(setOf("id", "text", "type", "tags", "tier", "confidence", "review", "createdAt", "frameId"), it.keys) }
        val m2 = mems.single { it["id"]!!.jsonPrimitive.content == "m2" }
        assertEquals("f2", m2["frameId"]!!.jsonPrimitive.content); assertEquals(0.5, m2["confidence"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("OK", m2["review"]!!.jsonPrimitive.content); assertEquals(2L, m2["createdAt"]!!.jsonPrimitive.content.toLong())
        assertEquals(kotlinx.serialization.json.JsonNull, mems.single { it["id"]!!.jsonPrimitive.content == "m1" }["frameId"])
        val ask = json["asks"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "at", "question", "answer"), ask.keys)
        assertEquals(2L, ask["at"]!!.jsonPrimitive.content.toLong()); assertEquals("milk?", ask["question"]!!.jsonPrimitive.content)
        f.delete()
    }

    /** B1: a failure mid-export leaves the previous export in place and no `.part` behind. */
    @Test fun aFailedExportLeavesThePreviousExportAndNoPart() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        repeat(600) { g.memories.add(LegacyImporter.toMemory("m$it", "short_term", 0.9, it.toLong(), "note $it")) }
        val old = MemoryExport.write(ctx, nowMs = 20)
        val failed = runCatching { MemoryExport.write(ctx, SpyDao(g.db.exportDao(), failOnMemoryCall = 2), nowMs = 21) }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertTrue(old.exists())
        assertEquals(listOf(old.name), MemoryExport.dir(ctx).listFiles()!!.map { it.name })
        old.delete()
    }

    /**
     * The race: the cleanup lists the folder (it sees the `.part`), then the export renames the `.part` to `.json`, then the cleanup deletes what it listed (the `.part` is gone) and
     * the JSON with the forgotten text stays. Driven deterministically: the cleanup runs on its own thread and stalls after listing while the export tries to publish.
     */
    @Test fun anExportPublishedWhileTheCleanupIsBetweenListingAndDeletingNeverSurvives() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.memories.add(com.rokid.context.memory.db.MemoryEntity("m1", null, "forgotten text", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, 1, false, "t"))
        val listed = java.util.concurrent.CountDownLatch(1)
        lateinit var cleanup: Thread
        MemoryExport.testAfterEnumerate = { listed.countDown(); Thread.sleep(400) } // the cleanup is slow between listing and deleting
        MemoryExport.testBeforePublish = { cleanup = Thread { MemoryExport.deleteCached(ctx) }.also { it.start() }; assertTrue(listed.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            runCatching { MemoryExport.write(ctx, nowMs = 5) } // fails (stale) with the fix; with the race it publishes the JSON
            cleanup.join(10_000)
        } finally { MemoryExport.testBeforePublish = null; MemoryExport.testAfterEnumerate = null }
        assertTrue("no export holds the forgotten text", java.io.File(ctx.cacheDir, "exports").listFiles().orEmpty().none { it.name.startsWith("proactive-context-memories-") })
        assertTrue(MemoryExport.write(ctx, nowMs = 6).exists()) // the next export works
    }

    /** 2026-10-25: an export never carries an invalid or hidden memory (forgotten, needs-review, stale-hidden derived, orphaned); nothing is purged. */
    @Test fun anExportCarriesOnlyValidVisibleMemories() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx); val db = g.db
        fun mem(id: String, frame: String? = null, review: com.rokid.context.memory.db.ReviewState = com.rokid.context.memory.db.ReviewState.OK, derived: Boolean = false) =
            com.rokid.context.memory.db.MemoryEntity(id, frame, "text $id", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, review, false, 1, derived, "text $id")
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("live", 1, "/live.jpg", "rokid_glasses"))
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("gone", 1, "/gone.jpg", "rokid_glasses"))
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("gone", 1))
        listOf(mem("typed"), mem("photo", "live"), mem("derivedOk", derived = true), mem("review", review = com.rokid.context.memory.db.ReviewState.NEEDS_REVIEW),
            mem("forgotten", review = com.rokid.context.memory.db.ReviewState.INVALIDATED), mem("orphan", "missing"), mem("sibling", "gone"), mem("siblingForgotten", "gone", review = com.rokid.context.memory.db.ReviewState.INVALIDATED),
            mem("derivedStale", derived = true), mem("srcGone", review = com.rokid.context.memory.db.ReviewState.INVALIDATED)).forEach { db.memoryDao().insert(it) }
        db.forgetDao().insertEdges(listOf(com.rokid.context.memory.db.MemorySourceEntity("derivedOk", "typed"), com.rokid.context.memory.db.MemorySourceEntity("derivedStale", "srcGone")))
        val json = Json.parseToJsonElement(MemoryExport.write(ctx, nowMs = 3).readText()).jsonObject
        assertEquals(setOf("typed", "photo", "derivedOk", "sibling"), json["memories"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
        // "gone" has a tombstone (locked after Forget FACT of its sibling) but its row stays: the surviving OK fact is still exported
        assertEquals("nothing was purged", 10, db.memoryDao().count())
    }
}
