package com.rokid.context.orchestration

import android.util.Log
import com.rokid.context.core.model.FileDurability
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Delete all against frame ingest (spec 6.1.1, Review Focus 3). [mutex] is held by Delete all while it deletes frame
 * files and by the phone's frame store while it writes one, so no JPEG lands half-way through the wipe. The wipe's
 * start time is kept on disk ([FILE] under filesDir) so a frame captured before it, still buffered on the glasses, is
 * acked and dropped when it arrives, even after a restart.
 */
object WipeFence {
    private const val TAG = "WipeFence"
    const val FILE = "wipe_started_at"

    val mutex: Mutex = Mutex()

    /** The last Delete all's start (phone wall clock); null when none ran or the file can't be read. */
    fun lastStartedAtMs(filesDir: File): Long? = try {
        File(filesDir, FILE).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    } catch (e: IOException) {
        null
    }

    /** Atomic (temp, fsync, rename, dir fsync). A failure is logged, never thrown: Delete all must go on. */
    fun markStarted(filesDir: File, nowMs: Long) {
        val target = File(filesDir, FILE)
        val tmp = File(filesDir, "$FILE.tmp")
        try {
            FileOutputStream(tmp).use { it.write(nowMs.toString().toByteArray()); it.fd.sync() }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            FileDurability.fsyncDir(filesDir)
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "wipe start not recorded: ${e.javaClass.simpleName}")
        }
    }
}
