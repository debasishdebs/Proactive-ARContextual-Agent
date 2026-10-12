package com.rokid.context.phone.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.ui.queues.QueueSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class QueuesAndTrackerTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private var snapshots = 0
    private val sent = mutableListOf<String>()
    private val actions = object : QueueActions {
        override suspend fun snapshot(): QueueSnapshot { snapshots++; return QueueSnapshot(1, 0, 2, 2, 0, 24, 5, 4, 99, "READ ENQUEUED attempts=0") }
        override suspend fun queueExplicitWrite(text: String): String { sent += "write:$text"; return "writeContext queued: abc" }
        override suspend fun queueReadContext(query: String): String { sent += "read:$query"; return "readContext queued: def" }
        override suspend fun drainNow(): String { sent += "drain"; return "Queue drain requested" }
    }
    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    @Test fun queuesLoadOnOpenAndRefreshAfterEachAction() = runBlocking {
        val vm = QueuesViewModel(actions).also { vms += it }
        assertEquals(99, withTimeout(5_000) { vm.state.first { it.snapshot != null } }.snapshot?.totalFramesCaptured)
        vm.onExplicitWriteChange("  I parked on level 3  ")
        vm.queueExplicitWrite()
        withTimeout(5_000) { vm.state.first { it.message == "writeContext queued: abc" } }
        assertEquals("", vm.state.value.explicitWrite)
        vm.onReadQueryChange("   ")
        vm.queueReadContext() // blank → ignored
        vm.drainNow()
        withTimeout(5_000) { vm.state.first { it.message == "Queue drain requested" } }
        assertEquals(listOf("write:I parked on level 3", "drain"), sent)
        assertEquals(3, snapshots)
    }

    @Test fun trackerFormatsRecentTraces() = runBlocking {
        graph.traces.upsert(SummaryTraceEntity("sum-1", "TRUSTED", Fixtures.at(Fixtures.TODAY, 9, 41), "frames=3 imported=1"))
        val vm = TrackerViewModel(graph.timeline, zone = { Fixtures.ZONE }).also { vms += it }
        val items = withTimeout(5_000) { vm.state.first { it.isNotEmpty() } }
        assertEquals("09:41:00", items.single().updatedAt)
        assertEquals("TRUSTED", items.single().state)
    }
}
