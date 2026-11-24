package com.rokid.context.data.frames

import android.content.Context
import com.rokid.context.core.log.PcLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Roaming data saver, Codex r2 P2-9: free-space backpressure for parked photos. Photos parked for Wi-Fi are never shed or deleted, so a long stay on roaming grows the disk without bound; this is the
 * proactive bound. While photo uploads are held back (the frame would be parked) and free storage is below [MIN_FREE_BYTES], new frames are NOT admitted (the inbox leaves the file where it is and
 * retries on the next pass; nothing is deleted) and [paused] says so for the Data saver status line. Admission resumes by itself when space is freed or Wi-Fi returns (nothing is parked then). Counts only.
 */
object ParkedStorageGuard {
    /** 1 GiB: below this the phone itself is in trouble, and the parked backlog stops growing. */
    const val MIN_FREE_BYTES = 1024L * 1024L * 1024L

    private val pausedFlag = AtomicBoolean(false)
    /** Free bytes on the app's storage (tests replace it). */
    @Volatile internal var freeBytes: (Context) -> Long = { it.filesDir.usableSpace }

    /** True while new photos are refused for lack of space (the status line says so). */
    fun paused(): Boolean = pausedFlag.get()

    /** Re-reads the state for a frame that [wouldPark] and returns whether it must be refused. A frame that would not park (Wi-Fi: it is described and deleted as usual) is never refused here. */
    fun refuses(context: Context, wouldPark: Boolean): Boolean {
        val low = wouldPark && try { freeBytes(context) < MIN_FREE_BYTES } catch (e: Exception) { false }
        if (pausedFlag.getAndSet(low) != low) { if (low) PcLog.i("ParkedStorage", "capture paused: storage low") else PcLog.i("ParkedStorage", "capture resumed") }
        return low
    }

    internal fun resetForTests() { pausedFlag.set(false); freeBytes = { it.filesDir.usableSpace } }
}
