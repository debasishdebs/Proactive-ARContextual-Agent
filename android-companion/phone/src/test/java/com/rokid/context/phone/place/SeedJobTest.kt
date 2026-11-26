package com.rokid.context.phone.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.rokid.context.core.net.DataMode
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** S3: the derived area state, the reconcile rule, the network rule, the progress store (persisted counts) and that no text carries a name or a position. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SeedJobTest {
    private fun d(w: WorkInfo.State?, p: SeedProgress = SeedProgress(40, 100), status: String? = AREA_SEEDING, blocked: Boolean = false) = SeedStates.derive(w, p, status, blocked)

    @Test fun runningShowsThePercentOfCellsDone() = assertEquals(SeedAreaState(SeedPhase.RUNNING, 40, counts = SeedProgress(40, 100)), d(WorkInfo.State.RUNNING))
    @Test fun enqueuedIsQueuedUnlessTheNetworkIsRefused() {
        assertEquals(SeedPhase.QUEUED, d(WorkInfo.State.ENQUEUED).phase)
        assertEquals(SeedPhase.QUEUED, d(WorkInfo.State.BLOCKED).phase)
        assertEquals(SeedAreaState(SeedPhase.WAITING_NETWORK, 40, counts = SeedProgress(40, 100)), d(WorkInfo.State.ENQUEUED, blocked = true))
        assertEquals(SeedPhase.WAITING_NETWORK, d(WorkInfo.State.ENQUEUED, SeedProgress(40, 100, waiting = true)).phase)
    }
    @Test fun finishedStates() {
        assertEquals(SeedAreaState(SeedPhase.DONE, 100), d(WorkInfo.State.SUCCEEDED, status = AREA_DONE))
        assertEquals(SeedPhase.FAILED, d(WorkInfo.State.SUCCEEDED, status = AREA_PARTIAL).phase)
        assertEquals(SeedPhase.CANCELLED, d(WorkInfo.State.SUCCEEDED, status = null).phase) // area removed while it ran
        assertEquals(SeedPhase.FAILED, d(WorkInfo.State.FAILED).phase)
        assertEquals(SeedPhase.CANCELLED, d(WorkInfo.State.CANCELLED).phase)
    }
    @Test fun noWorkNeverReadsAsActive() { // an area can never stay "seeding" forever
        for (st in listOf(AREA_SEEDING, AREA_PARTIAL, AREA_DONE, null)) assertFalse(st, d(null, status = st).active)
        assertEquals(SeedPhase.CANCELLED, d(null, SeedProgress(40, 100, cancelled = true)).phase)
        assertEquals(SeedPhase.DONE, d(null, status = AREA_DONE).phase)
        assertEquals(SeedPhase.FAILED, d(null, status = AREA_SEEDING).phase)
    }
    @Test fun percentIsClampedAndZeroWithoutATotal() {
        assertEquals(0, SeedProgress(5, 0).percent); assertEquals(100, SeedProgress(150, 100).percent); assertEquals(33, SeedProgress(1, 3).percent)
    }

    @Test fun reconcileSelectsOnlySeedingAreasWithoutLiveWork() {
        val areas = listOf("a" to AREA_SEEDING, "b" to AREA_SEEDING, "c" to AREA_DONE, "d" to AREA_PARTIAL)
        assertEquals(listOf("a"), SeedStates.stalled(areas, live = setOf("b")))
        assertEquals(listOf("a", "b"), SeedStates.stalled(areas, live = emptySet()))
        assertTrue(SeedStates.isLive(WorkInfo.State.ENQUEUED) && SeedStates.isLive(WorkInfo.State.RUNNING) && SeedStates.isLive(WorkInfo.State.BLOCKED))
        assertFalse(SeedStates.isLive(WorkInfo.State.CANCELLED) || SeedStates.isLive(WorkInfo.State.SUCCEEDED) || SeedStates.isLive(WorkInfo.State.FAILED) || SeedStates.isLive(null))
    }

    @Test fun anyNetworkByDefaultUnmeteredOnlyWhenTheGlobalWifiOnlyIsOn() {
        assertEquals(NetworkType.CONNECTED, SeedNetwork.requirement(DataMode.DEFAULT))
        assertEquals(NetworkType.CONNECTED, SeedNetwork.requirement(DataMode.WIFI_AND_HOME_MOBILE))
        assertEquals(NetworkType.CONNECTED, SeedNetwork.requirement(DataMode.ANYWHERE))
        assertEquals(NetworkType.UNMETERED, SeedNetwork.requirement(DataMode.WIFI_ONLY))
    }

    @Test fun progressIsPersistedAndMonotonicSoAResumeNeverGoesBack() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val k = SeedKeys.of("seed:17.300,78.400,17.400,78.500")
        SeedProgressStore(ctx).clearAll()
        SeedProgressStore(ctx).progress(k, 30, 100)
        val reopened = SeedProgressStore(ctx) // a new instance = a new process reading the same prefs
        assertEquals(SeedProgress(30, 100, okCells = 30), reopened.get(k))
        reopened.begin(k); reopened.progress(k, 2, 100) // the resume re-counts the finished cells from 0
        assertEquals(30, reopened.get(k).done)
        reopened.progress(k, 55, 100); assertEquals(55, reopened.get(k).percent)
        reopened.setWaiting(k, true); assertTrue(reopened.get(k).waiting)
        reopened.progress(k, 56, 100); assertFalse("running again clears the waiting flag", reopened.get(k).waiting)
        reopened.markCancelled(k); assertTrue(reopened.get(k).cancelled)
        reopened.clear(k); assertEquals(SeedProgress(), reopened.get(k))
    }

    @Test fun theTextShowsOverallOkAndFailedWithCountsAndPartialSeparately() {
        val p = SeedProgress(525, 2100, okCells = 200, failCells = 325)
        assertEquals("Seeding places 25% done (525 of 2100) \u00b7 38% ok (200) \u00b7 62% failed (325)", SeedStates.derive(WorkInfo.State.RUNNING, p, AREA_SEEDING, false).text)
        assertTrue(SeedStates.derive(WorkInfo.State.ENQUEUED, p.copy(partial = 12), AREA_SEEDING, true).text.let { it.startsWith("Seeding paused at 25% done (525 of 2100)") && it.contains("\u00b7 12 without OSM extras") })
        val failed = SeedStates.derive(WorkInfo.State.FAILED, SeedProgress(10, 10, failed = 3, okCells = 7, failCells = 3), AREA_PARTIAL, false)
        assertEquals("FAILED: 3 cells did not seed (100% done (10 of 10) \u00b7 70% ok (7) \u00b7 30% failed (3))", failed.text)
        assertEquals("Seeding done", SeedStates.derive(WorkInfo.State.SUCCEEDED, SeedProgress(), AREA_DONE, false).text)
        assertEquals("Seeding done \u00b7 12 cells without OSM extras", SeedStates.derive(WorkInfo.State.SUCCEEDED, SeedProgress(noOsm = 12), AREA_DONE, false).text) // partial cells do not make the area FAILED
        assertEquals("Seeding done \u00b7 1 cell without OSM extras", SeedStates.derive(null, SeedProgress(noOsm = 1), AREA_DONE, false).text)
    }

    @Test fun retryConvertsFailedToOkWhileProcessedNeverGoesBack() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val k = SeedKeys.of("seed:1.0,1.0,1.1,1.1"); val st = SeedProgressStore(ctx); st.clearAll()
        st.progress(k, 10, 10, failed = 4, partial = 1, fetched = 6)
        assertEquals(SeedProgress(10, 10, okCells = 6, failCells = 4, partial = 1), st.get(k))
        st.begin(k)                                   // Retry failed cells
        st.progress(k, 3, 10, failed = 1, fetched = 1) // re-walking: 1 of the 4 recovered so far
        var g = st.get(k); assertEquals(10, g.done); assertEquals(3, g.failCells); assertEquals(7, g.okCells)
        st.progress(k, 10, 10, failed = 2, partial = 0, fetched = 2) // end of the pass: exact
        g = st.get(k); assertEquals(10, g.done); assertEquals(2, g.failCells); assertEquals(8, g.okCells); assertEquals(0, g.partial)
        assertTrue(g.done == g.okCells + g.failCells)
    }

    @Test fun keysAndTextsCarryNoPositionOrName() {
        val id = "seed:17.300,78.400,17.400,78.500"
        val k = SeedKeys.of(id)
        assertEquals(16, k.length); assertTrue(k.all { it in "0123456789abcdef" })
        assertFalse(k.contains("17.3") || k.contains("78.4"))
        for (ph in SeedPhase.entries) {
            val t = SeedAreaState(ph, 42).text
            assertFalse(t, Regex("\\d+\\.\\d+").containsMatchIn(t)) // no coordinates
        }
        assertEquals("Seeding places 42%", SeedAreaState(SeedPhase.RUNNING, 42).text)
    }

    // S7: the phone's own per-cell timing: durations only, used once 10 cells are in, fading past 200
    @Test fun ownTimingNeedsTenCellsAndStoresOnlyDurations() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("place_seed_timing", Context.MODE_PRIVATE).edit().clear().commit()
        val t = SeedTimingStore(ctx)
        repeat(9) { t.add(4_000) }; assertEquals(null, t.secondsPerCell())
        t.add(4_000); assertEquals(4.0, t.secondsPerCell()!!, 0.001)
        repeat(300) { t.add(2_000) }
        assertEquals(2.0, t.secondsPerCell()!!, 0.1)
        assertEquals(setOf("n", "ms"), ctx.getSharedPreferences("place_seed_timing", Context.MODE_PRIVATE).all.keys) // nothing but a count and a sum
        assertEquals(null, SeedTimingStore.Timing.secondsPerCell(50, 0))
    }
}
