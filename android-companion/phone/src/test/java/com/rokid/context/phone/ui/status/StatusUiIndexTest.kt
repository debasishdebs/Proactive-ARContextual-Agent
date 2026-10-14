package com.rokid.context.phone.ui.status

import com.rokid.context.memory.IndexHealth
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerKind
import com.rokid.context.phone.ui.banner.IndexState
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusUiIndexTest {
    @Test fun indexStateReachesTheBanner() = runBlocking {
        val glasses = FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame()))
        val s = StatusUi.flow(glasses, FakePipelineControl(), flowOf(Fixtures.NOW), flowOf(10), flowOf(IndexState.of(IndexHealth.RUNNING))).first()
        assertEquals(BannerKind.INDEX, s.banner?.kind)
        assertEquals("Rebuilding memory index…", s.banner?.title)
        assertEquals(IndexState.NEEDED, IndexState.of(IndexHealth.NEEDED))
    }

    @Test fun anUnstableIndexAsksForARebuild() = runBlocking {
        assertEquals(IndexState.UNSTABLE, IndexState.of(IndexHealth.NONE, unstable = true, embedBound = true))
        assertEquals(IndexState.UNSTABLE, IndexState.of(IndexHealth.NEEDED, unstable = true, embedBound = true))
        assertEquals(IndexState.RUNNING, IndexState.of(IndexHealth.RUNNING, unstable = false, embedBound = true))
        assertEquals("no embedding model: nothing to rebuild", IndexState.NONE, IndexState.of(IndexHealth.NONE, unstable = true, embedBound = false))
        val glasses = FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame()))
        val b = StatusUi.flow(glasses, FakePipelineControl(), flowOf(Fixtures.NOW), flowOf(10), flowOf(IndexState.UNSTABLE)).first().banner
        assertEquals(BannerKind.INDEX, b?.kind)
        assertEquals("Memory index unstable", b?.title)
        assertEquals("Rebuild", b?.actionLabel)
        assertEquals(BannerAction.REBUILD_INDEX, b?.action)
    }

    @Test fun batterySaverReachesHomesBanner() = runBlocking {
        val glasses = FakeGlassesStatusSource(FakeGlassesStatusSource.connected(Fixtures.stateFrame()))
        val s = StatusUi.flow(glasses, FakePipelineControl(), flowOf(Fixtures.NOW), flowOf(10), batterySaver = flowOf(true)).first()
        assertEquals(BannerKind.BATTERY_SAVER, s.banner?.kind)
    }
}
