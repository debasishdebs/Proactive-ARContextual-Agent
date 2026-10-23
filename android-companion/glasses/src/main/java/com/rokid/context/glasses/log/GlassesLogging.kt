package com.rokid.context.glasses.log

import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Process-start wiring of the glasses logger (called once, from `GlassesRuntime`, through the `DiagLog` shim). */
object GlassesLogging {
    /**
     * THE single owner of the glasses LOG policy. The glasses diag log has always recorded every line (I and W) and must keep doing so, so the policy is ON.
     * LGC4 DECISION (LG-MIN, plan 0A): this does NOT follow the persisted `diag_record` flag. The flag drives the TRACE gate only (`DiagRecord.diagEnabled()`). The reason is
     * the reader hazard: `tools/battery-run.sh` and `tools/m1-run.sh` read the I-level `heartbeat v=2` lines from `capture_diag.log`; with diagnostics off those lines would no
     * longer be written and the tools would silently starve (battery summaries lose samples, M1 can report old rows or zero/default values). The log may follow the flag only
     * after both tools reject, or clearly label, a run without fresh heartbeat evidence (LGC10-min / a later package). No other code may call `PcLog.setDiagnostic` on the glasses
     * (pinned by `DiagRecordTest.theSourceNeverTouchesPcLogPolicy` and `GlassesLoggingTest.currentPolicyIsAlwaysOnWithASingleOwner`).
     */
    const val DIAGNOSTIC_RECORD = true

    private val ONCE = AtomicBoolean(false)

    /**
     * The directory `capture_diag.log` lives in, built WITHOUT touching the filesystem: `Context.getFilesDir()` checks and may create
     * the directory (main-thread IO when called from `Activity.onCreate`), `applicationInfo.dataDir` is a plain string. The writer thread
     * creates the directory on its first append.
     */
    fun logDir(context: android.content.Context): File = File(context.applicationInfo.dataDir, "files")

    /**
     * Installs the [GlassesLogSink] into PcLog exactly once per process (later calls return null). Nothing here touches the filesystem on
     * the calling thread: [filesDir] only supplies a path and the writer thread creates the directory on its first append. The diagnostic
     * snapshot is applied BEFORE [installer] drains PcLog's bootstrap ring, so early lines are routed with the policy in force. If the
     * install is refused the writer is closed and the once-guard re-armed.
     */
    fun install(
        filesDir: File,
        installer: (LogSink) -> Boolean = PcLog::install,
        once: AtomicBoolean = ONCE,
        setDiagnostic: (Boolean) -> Unit = PcLog::setDiagnostic,
        sinkFactory: (File) -> GlassesLogSink = { GlassesLogSink.create(it) },
    ): GlassesLogSink? {
        if (!once.compareAndSet(false, true)) return null
        val sink = try { sinkFactory(filesDir) } catch (t: Throwable) { once.set(false); return null }
        try { setDiagnostic(DIAGNOSTIC_RECORD) } catch (_: Throwable) { }
        val installed = try { installer(sink) } catch (_: Throwable) { false }
        if (!installed) { sink.close(); once.set(false); return null }
        return sink
    }
}
