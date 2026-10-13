package com.rokid.context.orchestration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Fix round 1 I1/I3: the Delete all fence survives clock changes and fails closed when it can't be recorded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WipeFenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val wall = 1_793_000_000_000L
    private val up = 50_000_000L
    private val hour = 3_600_000L

    @Test fun aBackwardClockJumpAfterAWipeStillStoresNewFrames() {
        val dir = tmp.newFolder()
        assertTrue(WipeFence.markStarted(dir, wall, up, "boot-a"))
        // 5 s later the clock is set back an hour: the fence moves with it, so a frame captured 1 s ago is after it.
        val f = WipeFence.fence(dir, nowMs = wall - hour + 5_000, elapsedMs = up + 5_000, bootId = "boot-a")
        assertEquals(WipeFence.Fence.At(wall - hour), f)
        assertTrue((f as WipeFence.Fence.At).ms < wall - hour + 4_000)
    }

    @Test fun aPlausibleFenceFromAnotherBootIsTrusted() {
        val dir = tmp.newFolder()
        WipeFence.markStarted(dir, wall, up, "boot-a")
        assertEquals(WipeFence.Fence.At(wall), WipeFence.fence(dir, nowMs = wall + hour, elapsedMs = 1_000, bootId = "boot-b"))
    }

    @Test fun anImplausibleFenceFromAnotherBootIsClampedToNowAndRewritten() {
        val dir = tmp.newFolder()
        WipeFence.markStarted(dir, wall, up, "boot-a")
        val now = wall - hour
        assertEquals(WipeFence.Fence.At(now), WipeFence.fence(dir, nowMs = now, elapsedMs = 1_000, bootId = "boot-b"))
        assertEquals(now, WipeFence.lastStartedAtMs(dir))
        assertEquals(WipeFence.Fence.At(now), WipeFence.fence(dir, nowMs = now + 10, elapsedMs = 1_010, bootId = "boot-b"))
    }

    @Test fun anOldSingleNumberRecordStillLoads() {
        val dir = tmp.newFolder()
        File(dir, WipeFence.FILE).writeText("$wall")
        assertEquals(WipeFence.Fence.At(wall), WipeFence.fence(dir, nowMs = wall + 1, elapsedMs = 1, bootId = "boot-a"))
    }

    @Test fun aFailedMarkIsUnknownUntilItCanBeWritten() {
        val dir = tmp.newFolder()
        val blocker = File(dir, WipeFence.FILE).apply { mkdirs(); File(this, "x").writeText("x") }
        assertFalse(WipeFence.markStarted(dir, wall, up, "boot-a"))
        assertTrue(WipeFence.fence(dir, nowMs = wall + 1, elapsedMs = up + 1, bootId = "boot-a") is WipeFence.Fence.Unknown)
        blocker.deleteRecursively()
        assertEquals("the pending record is written once it can be", WipeFence.Fence.At(wall), WipeFence.fence(dir, nowMs = wall + 2, elapsedMs = up + 2, bootId = "boot-a"))
    }

    @Test fun aFailureInAnEarlierProcessFailsClosedAtNow() {
        val dir = tmp.newFolder()
        File(dir, WipeFence.FILE).writeText("${wall - hour}")
        val marker = File(dir, WipeFence.MARKER).apply { writeText("") }
        assertEquals(WipeFence.Fence.At(wall), WipeFence.fence(dir, nowMs = wall, elapsedMs = up, bootId = "boot-a"))
        assertFalse(marker.exists())
    }
}
