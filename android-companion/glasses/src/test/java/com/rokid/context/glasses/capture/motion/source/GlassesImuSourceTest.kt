package com.rokid.context.glasses.capture.motion.source

import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.motion.model.HeuristicMotionModel
import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesImuSourceTest {
    @Test fun onSecondEmitsOneEvidence() = runTest(UnconfinedTestDispatcher()) {
        val src = GlassesImuSource(HeuristicMotionModel())
        assertEquals("glasses_imu", src.id)
        assertEquals(SourceKind.GLASSES_IMU, src.kind)
        assertTrue(src.available.value)
        val got = mutableListOf<MotionEvidence>()
        val job = launch { src.evidence.collect { got += it } }
        val ret = src.onSecond(SecondFeatures(0f, 0f, 0f, 0f, 0f, 0f, 0f), 5_000)
        job.cancel()
        assertEquals(1, got.size)
        assertEquals(ret, got[0])
        assertEquals(1.0f, got[0].confidence)
        assertEquals(5_000L, got[0].atMs)
        assertEquals(MotionState.STILL, got[0].state)
        assertEquals("glasses_imu", got[0].sourceId)
        assertTrue(src.available.value)
    }
}
