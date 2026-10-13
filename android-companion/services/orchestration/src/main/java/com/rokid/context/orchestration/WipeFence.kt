package com.rokid.context.orchestration

import android.os.SystemClock
import android.util.Log
import com.rokid.context.core.model.FileDurability
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Delete all against frame ingest (spec 6.1.1, Review Focus 3). [mutex] is held by Delete all while it deletes frame
 * files and by the phone's frame store while it writes one, so no JPEG lands half-way through the wipe. The wipe's
 * start is kept on disk ([FILE] under filesDir, as "wall elapsed bootId") so a frame captured before it, still buffered
 * on the glasses, is acked and dropped when it arrives, even after a restart.
 *
 * Clock changes (fix round 1 I1): within the boot that wrote it, the fence is re-based on uptime, so a clock change
 * moves it with the clock. From another boot the wall value is trusted while it is no more than [SLACK_MS] ahead of
 * now; otherwise it is clamped to now and rewritten.
 *
 * Failure (fix round 1 I3): a start that can't be recorded is kept in memory and retried on every [fence] call, and a
 * [MARKER] is left (best effort) for a later process. Until the record is written the fence is [Fence.Unknown], so the
 * phone stores no frame (fail closed). A marker without a pending record (an earlier process) clamps the fence to now.
 */
object WipeFence {
    private const val TAG = "WipeFence"
    const val FILE = "wipe_started_at"
    const val MARKER = "wipe_started_at.unknown"
    private const val SLACK_MS = 60_000L

    val mutex: Mutex = Mutex()

    sealed interface Fence {
        /** No Delete all on record. */
        data object None : Fence
        /** Frames captured before [ms] (phone wall clock) were covered by a Delete all. */
        data class At(val ms: Long) : Fence
        /** A Delete all ran but its start could not be recorded: store nothing until it is. */
        data class Unknown(val cause: Throwable?) : Fence
    }

    private class Record(val wallMs: Long, val elapsedMs: Long?, val bootId: String?)
    private class Pending(val dir: File, val wallMs: Long, val elapsedMs: Long, val bootId: String, val cause: Throwable)

    @Volatile private var pending: Pending? = null

    /** The recorded wall-clock start, as written; null when none (or unreadable). */
    fun lastStartedAtMs(filesDir: File): Long? = try { read(filesDir)?.wallMs } catch (e: IOException) { null }

    /** True once recorded (temp, fsync, rename, dir fsync). A failure is logged and kept for retry, never thrown. */
    @Synchronized fun markStarted(
        filesDir: File,
        nowMs: Long,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        bootId: String = currentBootId(),
    ): Boolean {
        val target = File(filesDir, FILE)
        val tmp = File(filesDir, "$FILE.tmp")
        return try {
            FileOutputStream(tmp).use { it.write("$nowMs $elapsedMs $bootId".trim().toByteArray()); it.fd.sync() }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            FileDurability.fsyncDir(filesDir)
            File(filesDir, MARKER).delete()
            if (pending?.dir == filesDir.absoluteFile) pending = null
            true
        } catch (e: IOException) {
            tmp.delete()
            pending = Pending(filesDir.absoluteFile, nowMs, elapsedMs, bootId, e)
            try { File(filesDir, MARKER).createNewFile() } catch (_: IOException) { }
            Log.w(TAG, "wipe start not recorded: ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized fun fence(
        filesDir: File,
        nowMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        bootId: String = currentBootId(),
    ): Fence {
        pending?.takeIf { it.dir == filesDir.absoluteFile }?.let { p ->
            if (!markStarted(filesDir, p.wallMs, p.elapsedMs, p.bootId)) return Fence.Unknown(pending?.cause)
        }
        if (File(filesDir, MARKER).exists()) return clamp(filesDir, nowMs, elapsedMs, bootId)
        val r = try { read(filesDir) } catch (e: IOException) { return clamp(filesDir, nowMs, elapsedMs, bootId) } ?: return Fence.None
        val e = r.elapsedMs
        return when {
            e != null && !r.bootId.isNullOrEmpty() && r.bootId == bootId && elapsedMs >= e -> Fence.At(nowMs - (elapsedMs - e))
            r.wallMs <= nowMs + SLACK_MS -> Fence.At(r.wallMs)
            else -> clamp(filesDir, nowMs, elapsedMs, bootId)
        }
    }

    /** The wipe's real start is unknowable: fail closed at now (frames captured before now are covered) and record it. */
    private fun clamp(filesDir: File, nowMs: Long, elapsedMs: Long, bootId: String): Fence {
        Log.i(TAG, "wipe fence clamped to now")
        return if (markStarted(filesDir, nowMs, elapsedMs, bootId)) Fence.At(nowMs) else Fence.Unknown(pending?.cause)
    }

    private fun read(filesDir: File): Record? {
        val f = File(filesDir, FILE)
        if (!f.isFile) return null
        val parts = f.readText().trim().split(' ')
        val wall = parts.getOrNull(0)?.toLongOrNull() ?: throw IOException("bad wipe record")
        return Record(wall, parts.getOrNull(1)?.toLongOrNull(), parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /** This boot's id from the kernel; "" when unreadable (then a record is never treated as this boot's). */
    internal fun currentBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (e: IOException) {
        ""
    } catch (e: SecurityException) {
        ""
    }
}
