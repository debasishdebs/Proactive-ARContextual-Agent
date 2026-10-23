package com.rokid.context.glasses.log

import android.util.Log
import com.rokid.context.core.log.AsyncLineWriter
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogFs
import com.rokid.context.core.log.RealLogFs
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.Route
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * The glasses' single log backend (always-on logging LGC3). It EXECUTES the [Route] that [com.rokid.context.core.log.LogPolicy]
 * decided and never decides itself: logcat when `route.logcat` (unchanged), and `files/capture_diag.log` through the bounded
 * [AsyncLineWriter] when `route.file`. [emit] never blocks (enqueue only); all disk work is on the writer thread.
 *
 * The file keeps its pre-LGC3 name, rotation (`capture_diag.log` and `capture_diag.log.1`, at most [MAX_BYTES] together) and line
 * format `MM-dd HH:mm:ss.SSS L Tag message`, because `tools/battery-run.sh`, `tools/m1-run.sh` and the heartbeat parsers read it over
 * `adb exec-out run-as com.rokid.context.glasses cat files/capture_diag.log`.
 *
 * Delete all / Forget do NOT touch this file (Q9: counts only), so nothing wires [clear] into a wipe; [fence] and [clear] exist
 * only as the writer's acknowledged API.
 */
class GlassesLogSink(
    private val writer: AsyncLineWriter,
    private val logcat: (Level, String, String) -> Unit = ::androidLog,
) : LogSink {
    override fun emit(line: Line, route: Route) {
        if (route.logcat) try { logcat(line.level, line.tag, line.msg) } catch (_: Throwable) { }
        if (route.file) writer.append(line)
    }

    fun fence(): CompletableFuture<Boolean> = writer.fence()
    fun clear(): CompletableFuture<Boolean> = writer.clear()

    /** Waits until nothing is queued or in flight (tests; flush before a pull). */
    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    fun close() = writer.close(drain = false)

    companion object {
        const val FILE_NAME = "capture_diag.log"
        /** Two files of at most half each (the writer's rule): 3 MB in total, as before. */
        const val MAX_BYTES = 3_000_000L

        /** Side-effect free: only builds paths; the writer thread creates the directory on its first append. [maxBytes] and [fs] are test seams. */
        fun create(filesDir: File, maxBytes: Long = MAX_BYTES, fs: LogFs = RealLogFs): GlassesLogSink =
            GlassesLogSink(AsyncLineWriter(File(filesDir, FILE_NAME), maxBytes = maxBytes, fs = fs))

        private fun androidLog(level: Level, tag: String, msg: String) {
            when (level) {
                Level.D -> Log.d(tag, msg); Level.I -> Log.i(tag, msg)
                Level.W -> Log.w(tag, msg); Level.E -> Log.e(tag, msg)
            }
        }
    }
}
