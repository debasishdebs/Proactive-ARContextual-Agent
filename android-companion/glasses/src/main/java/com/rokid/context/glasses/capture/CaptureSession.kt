package com.rokid.context.glasses.capture

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * One capture window's camera resources (Phase 8 C1). Created right after the SurfaceTexture, used with `use { }`, so a
 * bind failure, the window timeout, an exception or a cancellation all unbind the camera and release the texture exactly
 * once. Each step runs on its own: a throwing unbind still releases.
 */
internal class CaptureSession(private val unbind: () -> Unit, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching(unbind).onFailure { Log.w(CaptureService.TAG, "capture unbind failed: ${it.javaClass.simpleName}") }
        runCatching(release).onFailure { Log.w(CaptureService.TAG, "texture release failed: ${it.javaClass.simpleName}") }
    }
}

/**
 * Runs [start] (startForeground). A refusal (ForegroundServiceStartNotAllowedException, IllegalStateException, ...) logs
 * its class name and runs [onRefused], which schedules the next capture and stops this start. True when [start] ran.
 */
internal fun startForegroundOrStop(start: () -> Unit, onRefused: () -> Unit): Boolean = try {
    start()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(CaptureService.TAG, "startForeground refused: ${e.javaClass.simpleName}")
    onRefused()
    false
}
