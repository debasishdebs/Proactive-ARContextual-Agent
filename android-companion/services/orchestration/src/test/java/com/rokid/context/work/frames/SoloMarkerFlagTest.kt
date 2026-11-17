package com.rokid.context.work.frames

import com.rokid.context.memory.db.ForgetDao
import com.rokid.context.memory.db.FrameStateEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Forget lifecycle F1 / F5: a persisted solo marker batches alone only while `forgetV2` is ON; after ON to OFF batching is exactly v1. */
class SoloMarkerFlagTest {
    private fun row(id: String, marker: Boolean) = FrameStateEntity(id, "PENDING", 0, if (marker) ForgetDao.SOLO_REBUILD else null, null, null, 1L)
    private val rows = listOf(row("a", false), row("b", true), row("c", false))

    @Test fun onTheMarkedPhotoIsTheWholeBatch() = assertEquals(listOf("b"), KeyframePipeline.soloBatch(rows, true).map { it.frameId })
    @Test fun offTheMarkerIsIgnoredAndTheBatchIsUnchanged() = assertEquals(listOf("a", "b", "c"), KeyframePipeline.soloBatch(rows, false).map { it.frameId })
}
