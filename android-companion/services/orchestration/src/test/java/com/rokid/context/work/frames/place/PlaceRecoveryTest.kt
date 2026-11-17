package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

private val RPLACE = PlaceEntity("wd:Q1", null, "Charminar", "monument", 17.361944, 78.474722, 50, "A monument.", "wikipedia:en", "page:1", 1_700_000_000_000L, false, false, 0, 100)

/** Codex pg-r5 P1 #3: a committed place memory whose rollback failed is never readable, and recovery deletes it without a sampler poke. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceRecoveryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun frame(id: String) {
        val jpg = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        runBlocking { graph.db.frameDao().insertIgnore(FrameEntity(id, 1, jpg.absolutePath, "rokid_glasses")) }
    }

    private fun request(id: String) = PlaceMemoryRequest(id, RPLACE, null, 0.86f, true, MemoryWipe.generation.get())

    private suspend fun readableAnywhere(id: String): Boolean {
        val mid = PlaceMemory.idFor(id)
        val search = graph.memories.search("charminar", 10).any { it.id == mid }
        val list = graph.db.memoryViewDao().observeRecentVisible(50).first().any { it.id == mid }
        val export = graph.db.exportDao().memoryPage(0, 50).any { it.id == mid }
        val embed = graph.db.embeddingDao().missing("any", 50).any { it.id == mid }
        return search || list || export || embed
    }

    @Test fun aPendingMemoryIsInvisibleToSearchListExportAndTheEmbeddingIndexUntilRevealed() = runBlocking {
        frame("f1")
        assertTrue(PlaceMemory.save(ctx, request("f1"), pending = true))
        assertEquals("the row exists (so a rollback can find it)", 1, graph.db.memoryDao().count())
        assertFalse(readableAnywhere("f1")); assertEquals(0, graph.db.embeddingDao().eligibleCount())
        assertTrue(PlaceMemory.reveal(ctx, "f1", MemoryWipe.generation.get()))
        assertTrue(readableAnywhere("f1"))
    }

    @Test fun aRevealAfterDeleteAllOrForgetNeverResurrectsTheMemory() = runBlocking {
        frame("f1"); frame("f2")
        PlaceMemory.save(ctx, request("f1"), pending = true); PlaceMemory.save(ctx, request("f2"), pending = true)
        assertFalse("a wipe since the attempt began", PlaceMemory.reveal(ctx, "f1", MemoryWipe.generation.get() - 1))
        graph.db.forgetDao().invalidate(listOf(PlaceMemory.idFor("f2")))
        assertFalse(PlaceMemory.reveal(ctx, "f1", MemoryWipe.generation.get() - 1))
        assertFalse(readableAnywhere("f1")); assertFalse(readableAnywhere("f2"))
    }

    @Test fun threeFailedRollbacksLeaveTheMemoryHiddenAndMarkedAndTheRecoveryDeletesIt() = runBlocking {
        frame("f1")
        val ledger = PlaceMemory.ledger(ctx)
        var failures = 3; var scheduled = 0
        val holder = PlaceCardHolder()
        val s = PlaceSampler(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), store = object : PlaceFrameStore {
                override suspend fun newestDwell(sinceReceivedMs: Long) = PlaceFrame("f1", 9_999_000L)
                override suspend fun tombstoned(frameId: String) = false
            },
            fix = { PlaceFix(17.361944, 78.474722, 1_000, 10f) }, candidates = { _, _, _ -> PlaceCandidates(listOf(RPLACE), CellOutcome.FRESH) },
            match = { _, _ -> PlaceMatchResult(PlaceMatchOutput("1", 0.9f), true) },
            saveMemory = { r -> PlaceMemory.save(ctx, r, pending = true).also { holder.clear() } }, onCard = {}, clock = { 10_000_000L }, enabled = { true }, zoneBlocked = { false }, networkUp = { true },
            // the zone closes just after the commit: the card is refused and the memory must go, but the delete fails three times
            cards = holder,
            deleteMemory = { if (failures-- > 0) throw java.io.IOException("disk") else PlaceMemory.delete(ctx, it) }, ledger = ledger, onRollbackFailed = { scheduled++ },
            reveal = { id, gen -> PlaceMemory.reveal(ctx, id, gen) },
        )
        // drive commit through the public flow with the card refused (a stale epoch), then recover
        s.poke(); kotlinx.coroutines.delay(1500)
        assertEquals("marked after every try failed", setOf("f1"), ledger.pending())
        assertTrue("recovery was scheduled", scheduled >= 1)
        assertFalse("hidden meanwhile", readableAnywhere("f1"))
        // the recovery worker's sweep (no poke): deletes it
        assertTrue(PlaceRecovery.sweep(ctx))
        assertTrue(ledger.pending().isEmpty()); assertEquals(0, graph.db.memoryDao().count())
    }

    @Test fun theRecoverySweepWaitsForARunningAttemptAndRetriesAFailedDelete() = runBlocking {
        val ledger = InMemoryPlaceLedger().also { it.mark("a"); it.mark("b") }
        val flight = PlaceFlight()
        val deleted = mutableListOf<String>()
        flight.busy.set(true)
        assertFalse("an attempt owns the marks", PlaceRecovery.sweep(ledger, flight) { deleted += it })
        assertTrue(deleted.isEmpty())
        flight.busy.set(false)
        var fail = true
        assertFalse(PlaceRecovery.sweep(ledger, flight) { if (it == "a" && fail) throw java.io.IOException("x") else deleted += it })
        assertEquals(setOf("a"), ledger.pending()); assertFalse(flight.busy.get())
        fail = false
        assertTrue(PlaceRecovery.sweep(ledger, flight) { deleted += it }); assertTrue(ledger.pending().isEmpty())
    }

    @Test fun marksSurviveProcessDeathAndTheStartPathSchedulesTheRecovery() {
        PlaceMemory.ledger(ctx).mark("f1")
        // "a new process": a fresh ledger over the same prefs file, nothing held in memory
        assertEquals(setOf("f1"), PlaceMemory.ledger(ctx).pending())
        assertEquals(setOf("f1"), ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).getStringSet("pending_memory_takebacks", null))
        PlaceRecovery.ensureIfPending(ctx)
        val infos = androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(PlaceRecovery.NAME).get()
        assertEquals(1, infos.size)
        PlaceMemory.ledger(ctx).clear("f1"); PlaceRecovery.cancel(ctx)
    }
}
