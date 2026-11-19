package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.datasaver.DataSaverStore
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.forget.DeletionGraph
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.forget.NightIndexWorker
import com.rokid.context.orchestration.forget.SuspectSweepWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Roaming data saver, slice 1: the worker gate, the WorkManager constraints that follow the mode, and the wipe rule. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataSaverWorkGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private class FakeGate(var allow: Boolean) : DataGate {
        val asked = mutableListOf<DataKind>()
        val skippedLog = mutableListOf<Pair<DataKind, Skipped>>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision { asked += kind; return if (allow) DataDecision.ALLOW else DataDecision(false, "roaming") }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skippedLog += kind to what }
        override fun costlyNow() = !allow
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        ctx.getSharedPreferences(DataSaverStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { ctx.getSharedPreferences(DataSaverStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    // ---- the runtime gate ----

    @Test fun workersAskForBulkTrafficAndOnlyTheStartCountsASkip() {
        val g = FakeGate(allow = false)
        assertFalse(DataSaverWorkGate.open(g)); assertFalse(DataSaverWorkGate.open(g))
        assertTrue("open() never counts", g.skippedLog.isEmpty())
        assertFalse(DataSaverWorkGate.atStart(g))
        assertEquals(listOf(DataKind.BULK_OTHER to Skipped.BULK), g.skippedLog)
        assertEquals(setOf(DataKind.BULK_OTHER), g.asked.toSet())
    }

    @Test fun anOpenGateRunsAndCountsNothing() {
        val g = FakeGate(allow = true)
        assertTrue(DataSaverWorkGate.open(g)); assertTrue(DataSaverWorkGate.atStart(g)); assertTrue(g.skippedLog.isEmpty())
    }

    // ---- WorkManager constraints follow the mode ----

    private fun constraintOf(name: String) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }.constraints.requiredNetworkType

    @Test fun inWifiOnlyModeTheDescribeOnlyJobsWaitForAnUnmeteredNetworkAndTheNightJobDoesNot() {
        AppConfigStore.setPipelineEnabled(ctx, true)
        DataSaverStore.of(ctx).setMode(DataMode.WIFI_ONLY)
        OrchestrationScheduler(ctx).ensureNightIndex()
        assertEquals(NetworkType.UNMETERED, constraintOf(NightIndexWorker.CATCH_UP_NAME))
        assertEquals(NetworkType.UNMETERED, constraintOf(SuspectSweepWorker.NAME))
        assertEquals("the night job also does local index work", NetworkType.NOT_REQUIRED, constraintOf(NightIndexWorker.NAME))
    }

    @Test fun aModeChangeReachesTheJobsAlreadyScheduled() {
        AppConfigStore.setPipelineEnabled(ctx, true)
        DataSaverStore.of(ctx).setMode(DataMode.WIFI_ONLY)
        OrchestrationScheduler(ctx).ensureNightIndex()
        DataSaverStore.of(ctx).setMode(DataMode.WIFI_AND_HOME_MOBILE)
        OrchestrationScheduler(ctx).ensureNightIndex()
        assertEquals(NetworkType.NOT_REQUIRED, constraintOf(NightIndexWorker.CATCH_UP_NAME))
        assertEquals(NetworkType.NOT_REQUIRED, constraintOf(SuspectSweepWorker.NAME))
        DataSaverStore.of(ctx).setMode(DataMode.WIFI_ONLY)
        OrchestrationScheduler(ctx).ensureNightIndex()
        assertEquals(NetworkType.UNMETERED, constraintOf(NightIndexWorker.CATCH_UP_NAME))
    }

    // ---- Delete all ----

    @Test fun deleteAllLeavesTheDataSaverSettingsAlone() {
        val entry = DeletionGraph.PREFS.single { it.name == DataSaverStore.PREFS }
        assertEquals(DeletionGraph.Fate.KEPT, entry.fate)
        assertFalse(DataSaverStore.PREFS in MemoryWipe.WIPED_PREFS)
        assertFalse(DataSaverStore.PREFS in DeletionGraph.wipedPrefs)
    }
}
