package com.rokid.context.glasses.capture.diag

import com.rokid.context.core.log.PcLog
import com.rokid.context.glasses.log.GlassesLogging
import android.content.Context
import java.io.File

/**
 * LGC3: a thin shim over [PcLog] so the existing call sites keep their shape. [i] and [w] go to logcat and to
 * `files/capture_diag.log` exactly as before, now through `GlassesLogSink` and the bounded async writer. The glasses raw
 * `android.util.Log` calls are NOT routed here (accepted gap G2).
 */
object DiagLog {
    /** Installs the glasses sink once per process; does no filesystem work on the calling thread. */
    fun install(filesDir: File) { GlassesLogging.install(filesDir) }

    /** Production entry: derives the directory from `applicationInfo.dataDir` (a string), never `filesDir` (a getter that does IO). */
    fun install(app: Context) { GlassesLogging.install(GlassesLogging.logDir(app)) }

    fun i(tag: String, msg: String) = PcLog.i(tag, msg)
    fun w(tag: String, msg: String) = PcLog.w(tag, msg)
}
