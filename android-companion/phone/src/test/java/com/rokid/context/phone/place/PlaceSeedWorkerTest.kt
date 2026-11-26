package com.rokid.context.phone.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.CellFetch
import com.rokid.context.memory.place.CellSource
import com.rokid.context.memory.place.FetchedPlace
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.memory.place.Geohash5
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacesDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S3: the seed worker (resume, network waits, Offline only), the unique work (KEEP, network rule, cancel, Delete all) and the reconcile, over an in-memory pack and a fake source. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaceSeedWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PlacesDatabase
    private var t = 1_000_000_000L
    private val box = GeoBox(17.30, 78.40, 17.45, 78.55)
    private val id = "seed:17.300,78.400,17.450,78.550"
    private val key = SeedKeys.of(id)
    private val asked = mutableListOf<String>()
    private val source = object : CellSource {
        override suspend fun fetchCell(cell: String): CellFetch { asked += cell; val b = Geohash5.bounds(cell)!!; return CellFetch(listOf(FetchedPlace("$cell:p", null, "n", PlaceKinds.MONUMENT, b.south + 0.01, b.west + 0.01, 60, "s", "wikipedia:en", "page:1"))) }
    }
    private lateinit var pack: PlacePack
    private var refuse: String? = null

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        SeedProgressStore(ctx).clearAll()
        db = PlacesDatabase.inMemory(ctx)
        pack = PlacePack(db, source, PlacePack.DEFAULT_BUDGET_BYTES, { t }, { }, { })
        PlaceSeedWorker.packOf = { pack }
        PlaceSeedWorker.admission = { { refuse } }
    }

    @After fun tearDown() { db.close(); PlaceSeedWorker.packOf = { com.rokid.context.work.frames.place.PlaceGraph.readyPack(it) }; PlaceSeedWorker.admission = ::seedAdmission }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<PlaceSeedWorker>(ctx).setInputData(workDataOf(PlaceSeedWorker.KEY to key)).build().doWork()
    }

    private fun infos(name: String): List<WorkInfo> = runBlocking { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(name).first() }

    private val cells get() = Geohash5.cellsCovering(box)

    @Test fun aWholeSeedFinishesDoneAndClearsItsProgress() = runBlocking {
        pack.beginArea(id, box)
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(cells, asked); assertEquals(AREA_DONE, db.dao().areas().single().status)
        assertEquals(SeedProgress(), SeedProgressStore(ctx).get(key))
    }

    @Test fun noNetworkWaitsAndRetriesWithoutFetchingAndTheProgressSurvives() = runBlocking {
        pack.beginArea(id, box)
        refuse = SeedGate.NO_NETWORK
        assertEquals(ListenableWorker.Result.retry(), run())
        assertTrue(asked.isEmpty()); assertTrue(SeedProgressStore(ctx).get(key).waiting)
    }

    @Test fun aNetworkLostMidSeedPausesThenTheRetryResumesAndSkipsTheFinishedCells() = runBlocking {
        pack.beginArea(id, box)
        PlaceSeedWorker.admission = { { if (asked.size >= 2 && refuse == null) SeedGate.NO_NETWORK else null } }
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(2, asked.size)
        val saved = SeedProgressStore(ctx).get(key); assertTrue(saved.waiting); assertEquals(cells.size, saved.total); assertEquals(2, saved.done)
        // the network is back (a new process: a new store and a new worker): only the unfinished cells are fetched
        PlaceSeedWorker.admission = { { null } }
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(cells, asked) // every cell exactly once, in order
        assertEquals(AREA_DONE, db.dao().areas().single().status)
    }

    @Test fun offlineOnlyFailsWithoutAnyFetch() = runBlocking {
        pack.beginArea(id, box); refuse = SeedGate.OFFLINE_ONLY
        assertEquals(ListenableWorker.Result.failure(), run()); assertTrue(asked.isEmpty())
    }

    @Test fun aRemovedAreaEndsQuietly() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), run()); assertTrue(asked.isEmpty())
    }

    @Test fun theWorkDataAndNameCarryOnlyAHashNeverTheBox() {
        val w = SeedWork(ctx, wifiOnly = { false })
        assertFalse(w.uniqueName(id).contains("17.3")); assertTrue(w.uniqueName(id).endsWith(key))
        w.enqueue(id)
        val info = infos(w.uniqueName(id)).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(PlaceSeedWorker.TAG_ALL in info.tags)
        assertFalse(info.tags.joinToString().contains("17.3"))
    }

    @Test fun theNetworkRuleIsAnyNetworkByDefaultAndUnmeteredOnlyWithTheGlobalSetting() {
        assertEquals(NetworkType.CONNECTED, SeedWork(ctx, wifiOnly = { false }).constraints().requiredNetworkType)
        assertEquals(NetworkType.UNMETERED, SeedWork(ctx, wifiOnly = { true }).constraints().requiredNetworkType)
    }

    @Test fun seedingTheSameAreaTwiceKeepsOneWorkAndCancelStopsIt() {
        val w = SeedWork(ctx, wifiOnly = { false })
        w.enqueue(id); w.enqueue(id)
        val wm = WorkManager.getInstance(ctx)
        assertEquals(1, infos(w.uniqueName(id)).size)
        w.cancel(id)
        assertEquals(WorkInfo.State.CANCELLED, infos(w.uniqueName(id)).single().state)
        assertTrue(SeedProgressStore(ctx).get(key).cancelled)
    }

    @Test fun deleteAllCancelsEveryQueuedSeedAndItsProgress() = runBlocking {
        val w = SeedWork(ctx, wifiOnly = { false })
        w.enqueue(id); w.enqueue("seed:1.000,2.000,3.000,4.000")
        SeedProgressStore(ctx).progress(key, 3, 10)
        SeedWipeListener(ctx).onDeleteAll()
        val wm = WorkManager.getInstance(ctx)
        assertTrue(runBlocking { wm.getWorkInfosByTagFlow(PlaceSeedWorker.TAG_ALL).first() }.all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(SeedProgress(), SeedProgressStore(ctx).get(key))
    }

    @Test fun reconcileTurnsAStuckSeedingAreaIntoPartialButLeavesLiveWorkAlone() = runBlocking {
        val w = SeedWork(ctx, wifiOnly = { false })
        val live = "seed:5.000,6.000,7.000,8.000"
        pack.beginArea(id, box); pack.beginArea(live, GeoBox(5.0, 6.0, 5.1, 6.1))
        w.enqueue(live)
        t += 60_000L
        assertEquals(1, w.reconcile(pack))
        val by = pack.areas().associate { it.blueZoneId to it.status }
        assertEquals(AREA_PARTIAL, by[id]); assertEquals(AREA_SEEDING, by[live])
        assertEquals(0, w.reconcile(pack))
        // the states: the live one is queued/waiting and active, the stalled one is not
        val states = w.states(pack, blocked = false)
        assertTrue(states.getValue(live).active); assertFalse(states.getValue(id).active); assertNotNull(states[id])
    }
}
