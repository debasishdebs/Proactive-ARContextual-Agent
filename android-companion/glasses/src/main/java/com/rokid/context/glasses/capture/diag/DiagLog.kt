package com.rokid.context.glasses.capture.diag

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fix wave K4: a small rotating text file of everything the glasses log under PcCapture / PcUplink at INFO and up, because
 * logd lost our lines in retest 1. Counts and states only, never user text (the callers already keep to that).
 * Two files of at most [maxBytes] / 2 each (`capture_diag.log`, the previous one `capture_diag.log.1`), so at most [maxBytes] on disk.
 * Read it with `adb exec-out run-as com.rokid.context.glasses cat files/capture_diag.log`.
 */
class DiagFile(private val file: File, private val maxBytes: Long = MAX_BYTES, private val wallClock: () -> Long = System::currentTimeMillis) {
    private val older = File(file.path + ".1")
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun append(level: Char, tag: String, msg: String) {
        runCatching {
            if (file.length() >= maxBytes / 2) {
                older.delete()
                file.renameTo(older)
            }
            file.parentFile?.mkdirs()
            file.appendText("${fmt.format(Date(wallClock()))} $level $tag ${msg.replace('\n', ' ')}\n")
        }
    }

    companion object { const val MAX_BYTES = 3_000_000L }
}

/** The process-wide sink: [i] and [w] log to logcat as before AND to the file once [install]ed. */
object DiagLog {
    @Volatile private var sink: DiagFile? = null

    fun install(filesDir: File) { if (sink == null) sink = DiagFile(File(filesDir, "capture_diag.log")) }

    fun i(tag: String, msg: String) { runCatching { Log.i(tag, msg) }; sink?.append('I', tag, msg) }
    fun w(tag: String, msg: String) { runCatching { Log.w(tag, msg) }; sink?.append('W', tag, msg) }
}
