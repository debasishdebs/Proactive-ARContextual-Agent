package com.rokid.context.orchestration

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.Modality
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.IndexMaintenance.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** What the index log writes, seen through a real [PcLog] sink with diagnostics off: milestones reach the file route, plain I lines do not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IndexMaintenanceLogTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val spaces = EmbeddingSpaces(db.embeddingDao())
    private val memories = MemoryRepository(db.memoryDao())
    private val lines = CopyOnWriteArrayList<Pair<Line, Route>>()
    private var bulk = true
    private var t = 1_000_000L

    private val embedders = object : IndexEmbedders {
        val e = object : Embedder {
            override val fingerprint = "fp-test"
            override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
            override suspend fun embedQuery(text: String) = floatArrayOf(1f, 0f)
            override val imageDims: Int? get() = 3
            override val modalities get() = setOf(Modality.TEXT, Modality.IMAGE)
            override suspend fun embedImages(jpegs: List<ByteArray>) = jpegs.map { floatArrayOf(1f, 1f, 1f) }
        }
        override suspend fun bound() = BoundEmbedder(e, "{}", 2)
        override suspend fun forSpace(space: EmbeddingSpaceEntity): Embedder? = e
    }
    private val clock = object : IndexClock {
        var at = 0L
        override fun lastCanaryAt() = at
        override fun markCanary(at: Long) { this.at = at }
        override fun rebuildPending() = false
        override fun setRebuildPending(pending: Boolean) = Unit
        override fun consumedRebuild(): String? = null
        override fun consumeRebuild(token: String) = Unit
        override fun lastDiscard(): DiscardRecord? = null
        override fun recordDiscard(record: DiscardRecord?) = Unit
    }
    private val limiter = ProgressLimiter({ t })
    private val state = IndexLogState({ t })

    @Before fun sink() {
        PcLog.testOnlyResetNeverCallFromProduction()
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { lines += line to route } })
    }
    @After fun close() { PcLog.testOnlyResetNeverCallFromProduction(); db.close() }

    private fun maintenance(reason: () -> String = { "battery 40% and not charging" }) = IndexMaintenance(
        spaces, embedders, clock, { t }, batch = 2, maxPerRun = 256,
        photos = { ids -> ids.associateWith { PhotoRead.Bytes(byteArrayOf(1)) } },
        bulkAllowed = { bulk }, bulkReason = reason, imageBatch = 2, imageMaxPerRun = 200, progress = limiter, logState = state,
    )

    private suspend fun mem(id: String) = memories.add(MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, ""))
    private suspend fun photo(id: String, ts: Long) = db.frameDao().insertIgnore(FrameEntity(id, ts, "/p/$id", "rokid_glasses"))
    private fun milestones() = lines.filter { it.first.milestone }.map { it.first.msg }
    private fun count(prefix: String) = milestones().count { it.startsWith(prefix) }

    @Test fun repeatedPausedRunsWithUnchangedStateWriteTheMilestonesOnce() = runBlocking {
        mem("m1"); photo("p1", 10); photo("p2", 20); photo("p3", 30)
        bulk = false
        repeat(6) { assertEquals(Outcome.DONE, maintenance().run()); t += 1_000 }   // text complete, photos wait, gate closed, nothing changes
        assertEquals(1, count("index build start")); assertEquals(0, count("index build resumed"))
        assertEquals(1, count("index swap waiting")); assertEquals(1, count("index paused (images)"))
        assertEquals("one health line", 1, count("index health:"))
        assertTrue("all of them reach the file with diagnostics off", lines.filter { it.first.milestone }.all { it.second.file })
        assertTrue("no plain I line reaches the file", lines.filter { !it.first.milestone && it.first.level == Level.I }.none { it.second.file })
    }

    @Test fun aSecondProcessLogsTheResumedBuildOnceAndASwapWaitChangeLogsAgain() = runBlocking {
        mem("m1"); photo("p1", 10); photo("p2", 20)
        bulk = false
        maintenance().run()
        val fresh = IndexLogState({ t })                                             // a restart: nothing was said yet
        repeat(3) { IndexMaintenance(spaces, embedders, clock, { t }, photos = { ids -> ids.associateWith { PhotoRead.Bytes(byteArrayOf(1)) } }, bulkAllowed = { bulk }, progress = limiter, logState = fresh).run() }
        assertEquals(1, count("index build resumed"))
        assertEquals(2, count("index swap waiting"))                                   // once per state (first process, then the restarted one), not per run
    }

    @Test fun aFinishedBuildSwapsAndLeavesNoProgressKeysAndOneReadyLine() = runBlocking {
        mem("m1"); mem("m2"); photo("p1", 10)
        assertEquals(Outcome.DONE, maintenance().run())
        assertEquals(1, count("index build complete, swapped in")); assertEquals(1, count("semantic search ready"))
        assertEquals("a swapped space leaves no key in the limiter", 0, limiter.tracked())
        assertTrue(lines.any { !it.first.milestone && it.first.msg.startsWith("text index (building): ") })
        maintenance().run(); maintenance().run()
        assertEquals("ready is said once per process and space", 1, count("semantic search ready"))
    }

    @Test fun aThrowingReasonNeverChangesTheRun() = runBlocking {
        mem("m1"); photo("p1", 10)
        bulk = false
        assertEquals(Outcome.DONE, maintenance(reason = { throw IllegalStateException("gate read failed") }).run())
        assertEquals(1, count("index paused (images)"))
        assertTrue(milestones().any { it.contains("bulk gate closed") })
    }

    @Test fun progressWithinTheIntervalWritesNoLineAndTakesNoCount() = runBlocking {
        mem("m1"); mem("m2")
        maintenance().run()
        val active = spaces.queryable()!!
        limiter.due("text:${active.id}", 1, 100)            // a line was just written for this key: nothing may be due by time
        lines.clear(); t += 1_000
        mem("m3")
        maintenance().run()
        assertTrue("no progress line in the window", lines.none { it.first.msg.startsWith("text index") })
        assertEquals(false, limiter.mayLog("text:${active.id}"))
    }
}
