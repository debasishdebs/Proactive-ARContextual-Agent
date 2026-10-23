package com.rokid.context.phone.log

import com.rokid.context.core.log.LogPort
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.forget.ForgetListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Process-start wiring of the phone logger (called from `ContextApp.onCreate` before any other component logs). */
object PhoneLogging {
    private val ONCE = AtomicBoolean(false)

    /**
     * Installs the [PhoneLogSink] into [PcLog] exactly once per process (later calls return null and change nothing; a refused
     * install closes its writer and re-arms). The Developer mode flag is read OUTSIDE the log path: [developerMode] runs inside
     * [scope] (give it an IO dispatcher: it may load SharedPreferences), and every value, the persisted one first, is pushed with
     * [setDiagnostic]. The logging path only reads PcLog's volatile snapshot.
     */
    fun install(
        filesDir: File,
        developerMode: () -> StateFlow<Boolean>,
        scope: CoroutineScope,
        installer: (LogSink) -> Boolean = PcLog::install,
        once: AtomicBoolean = ONCE,
        setDiagnostic: (Boolean) -> Unit = PcLog::setDiagnostic,
    ): PhoneLogSink? {
        if (!once.compareAndSet(false, true)) return null
        val sink = PhoneLogSink.create(filesDir)
        val installed = try { installer(sink) } catch (_: Throwable) { false }
        if (!installed) { sink.close(); once.set(false); return null }
        scope.launch { try { developerMode().collect { setDiagnostic(it) } } catch (_: Throwable) { } }
        return sink
    }

    /** The port 3b's canvas code logs through: gated by [PcLog], so it can never bypass the policy. */
    fun canvasPort(): LogPort = PcLog.port("canvas")
}

/** Delete all clears the diagnostic log files (both rotations) and waits for the writer's acknowledgement. Never throws. */
class LogWipeListener(private val sink: PhoneLogSink) : ForgetListener {
    override suspend fun onDeleteAll() {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sink.wipe() }
    }
}
