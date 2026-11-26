package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentLocationGraph
import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.LocatedMoment
import com.rokid.context.memory.db.SceneLocatedMoment
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** review2 #6: a location read that finishes after "Delete saved locations" (or a Forget) never publishes the old coordinates; the map reloads instead. Synthetic positions only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityMapDeletionFenceTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph = TestGraph.fresh(ctx)
    private var model: ActivityMapViewModel? = null
    @After fun tearDown() { model?.cancelScope(); TestGraph.close(ctx) }

    /** The first read of [moments] parks until [release] (it has already "read" its rows); later reads answer [after]. */
    private class Gated(val after: List<LocatedMoment>) : MomentMapSource {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); private var n = 0
        override suspend fun moments(range: LongRange): List<LocatedMoment> {
            if (n++ == 0) { val old = listOf(LocatedMoment("a", 17.0, 78.0, 1)); entered.complete(Unit); release.await(); return old }
            return after
        }
        override suspend fun scenes(range: LongRange): List<SceneLocatedMoment> = emptyList()
        override suspend fun members(momentIds: List<String>): List<SceneMember> = emptyList()
    }

    @Test fun aReadFinishingAfterDeleteSavedLocationsNeverPublishesTheOldPoints() = runBlocking<Unit> {
        val src = Gated(after = emptyList())
        val vm = ActivityMapViewModel(src, graph.timeline).also { model = it }
        vm.load(null, Granularity.MOMENTS)
        withTimeout(5_000) { src.entered.await() }
        MomentLocationGraph.deleteAll(ctx) // commits while the read is still parked
        src.release.complete(Unit)
        withTimeout(5_000) { while (!(vm.state.value.loaded && vm.state.value.points.isEmpty())) kotlinx.coroutines.delay(20) } // the reload answers empty
        assertTrue("the old coordinates were never published", vm.state.value.points.isEmpty())
        assertEquals(0, vm.state.value.points.size)
    }
}
