package com.rokid.context.glasses.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Phase 8 C1: every SurfaceTexture is released exactly once, and a refused foreground start stops cleanly. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CaptureSessionTest {
    private var unbinds = 0
    private var releases = 0

    @Test fun closeReleasesOnceEvenWhenCalledTwice() {
        val s = CaptureSession(unbind = { unbinds++ }, release = { releases++ })
        s.close(); s.close()
        assertEquals(1, unbinds)
        assertEquals(1, releases)
    }

    @Test fun aBindFailureStillReleasesTheTexture() {
        val thrown = runCatching {
            CaptureSession(unbind = { unbinds++ }, release = { releases++ }).use { throw IllegalStateException("bind") }
        }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(1, unbinds)
        assertEquals(1, releases)
    }

    @Test fun aThrowingUnbindStillReleases() {
        CaptureSession(unbind = { throw IllegalStateException("unbind") }, release = { releases++ }).close()
        assertEquals(1, releases)
    }

    @Test fun aRefusedStartSchedulesTheNextCaptureAndStops() {
        var refused = 0
        val ok = startForegroundOrStop(start = { throw IllegalStateException("not allowed") }, onRefused = { refused++ })
        assertFalse(ok)
        assertEquals(1, refused)
    }

    @Test fun anAllowedStartDoesNotStop() {
        var refused = 0
        assertTrue(startForegroundOrStop(start = {}, onRefused = { refused++ }))
        assertEquals(0, refused)
    }

    @Test fun surfaceTexturesAreOnlyCreatedInsideACaptureSession() {
        val src = File("src/main/java/com/rokid/context/glasses/capture/CaptureService.kt").readLines()
        val bad = src.withIndex().filter { (_, line) ->
            val at = line.indexOf("SurfaceTexture(")
            at >= 0 && line.indexOf("CaptureSession(").let { it < 0 || it > at }
        }
        assertTrue("SurfaceTexture( outside a CaptureSession( expression at lines ${bad.map { it.index + 1 }}", bad.isEmpty())
    }
}
