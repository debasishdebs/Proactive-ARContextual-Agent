package com.rokid.context.phone.log

import android.util.Log
import com.rokid.context.core.log.AsyncLineWriter
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogFiles
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.Route
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The phone's single log backend (always-on logging LGC2; consumed by 3b through `PcLog.port`). It EXECUTES the [Route] that
 * [com.rokid.context.core.log.LogPolicy] decided and never decides itself: logcat when `route.logcat` (the in-app Logs screen
 * reads logcat, unchanged), and the diagnostic file `files/logs/pca.log` through the bounded [AsyncLineWriter] when
 * `route.file`. [emit] never blocks (enqueue only); all disk work is on the writer thread.
 */
class PhoneLogSink(
    private val writer: AsyncLineWriter,
    private val logcat: (Level, String, String) -> Unit = ::androidLog,
) : LogSink {
    override fun emit(line: Line, route: Route) {
        if (route.logcat) logcat(line.level, line.tag, line.msg)
        if (route.file) writer.append(line)
    }

    /**
     * Delete all hook: fences the writer (queued lines older than this call are discarded) and deletes every segment on the
     * writer thread, then waits for its acknowledgement. False on timeout, failure or a closed writer (the caller retries or reports).
     */
    fun wipe(timeoutMillis: Long = 5_000): Boolean =
        try { writer.clear().get(timeoutMillis, TimeUnit.MILLISECONDS) == true } catch (_: Throwable) { false }

    /** Waits until nothing is queued or in flight (tests; flush before a pull). */
    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    fun close() = writer.close(drain = false)

    companion object {
        /** Under `filesDir`; excluded from backup and device transfer (backup_rules.xml, data_extraction_rules.xml). */
        const val LOG_DIR = "logs"
        /**
         * Trip-sized retention (the user wants the FULL log of a multi-day trip to analyse afterwards): 60 files of at most [SEGMENT_BYTES]
         * (`pca.log` active, `pca.log.1` newest retained ... `pca.log.59` oldest), 300 MB in all, oldest dropped first. App-private, never uploaded.
         */
        const val MAX_BYTES = 300_000_000L
        const val SEGMENT_BYTES = 5_000_000L

        /** Side-effect free: only builds paths; the writer thread creates the directory on its first append. [maxBytes] and [segmentBytes] are test seams. */
        fun create(filesDir: File, maxBytes: Long = MAX_BYTES, segmentBytes: Long = minOf(SEGMENT_BYTES, maxBytes / 2)): PhoneLogSink =
            PhoneLogSink(AsyncLineWriter(File(File(filesDir, LOG_DIR), LogFiles.PCA_LOG), maxBytes = maxBytes, segmentBytes = segmentBytes))

        private fun androidLog(level: Level, tag: String, msg: String) {
            when (level) {
                Level.D -> Log.d(tag, msg); Level.I -> Log.i(tag, msg)
                Level.W -> Log.w(tag, msg); Level.E -> Log.e(tag, msg)
            }
        }
    }
}
