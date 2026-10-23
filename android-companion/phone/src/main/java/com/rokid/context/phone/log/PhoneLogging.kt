package com.rokid.context.phone.log

import android.content.Context
import com.rokid.context.core.log.LogPort
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.forget.ForgetListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Durable "a log wipe was requested and not yet acknowledged" flag (owned by the sink owner, not by the log path). */
interface WipePendingStore {
    fun pending(): Boolean
    fun set(pending: Boolean)
}

/** SharedPreferences-backed [WipePendingStore]; prefs are opened lazily so constructing it never touches disk. */
class PrefsWipePending(context: Context) : WipePendingStore {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    override fun pending(): Boolean = try { prefs.getBoolean(KEY, false) } catch (_: Throwable) { false }
    override fun set(pending: Boolean) { try { prefs.edit().putBoolean(KEY, pending).commit() } catch (_: Throwable) { } }
    companion object { const val PREFS = "log_wipe_state"; const val KEY = "wipe_pending" }
}

/** Clears the diagnostic log with a durable pending request: set BEFORE the wipe, cleared only on the writer's acknowledged success. */
class LogWiper(private val sink: PhoneLogSink, private val store: WipePendingStore, private val timeoutMillis: Long = 5_000) {
    fun wipe(): Boolean {
        store.set(true)
        val ok = sink.wipe(timeoutMillis)
        if (ok) store.set(false)
        return ok
    }
    /** At the next process start (same install path): finishes a wipe whose acknowledgement never arrived. */
    fun retryPending() { if (store.pending()) wipe() }
}

class PhoneLogInstall(val sink: PhoneLogSink, val wiper: LogWiper, val ready: Job)

/** Process-start wiring of the phone logger (called from `ContextApp.onCreate` before any other component logs). */
object PhoneLogging {
    private val ONCE = AtomicBoolean(false)

    /**
     * Installs the [PhoneLogSink] into [PcLog] exactly once per process (later calls return null; a refused install closes its writer and
     * re-arms). Nothing here touches the filesystem on the calling thread: [filesDir] must be a side-effect-free path, the writer creates
     * the directory. On [scope] (an IO dispatcher): the persisted Developer mode value is loaded and applied with [setDiagnostic]
     * BEFORE [installer] drains PcLog's bootstrap ring (so early D/I records are routed with the right snapshot), then the sink is
     * installed, a pending wipe is retried, and the flag is followed. The logging path only reads PcLog's volatile snapshot.
     */
    fun install(
        filesDir: File,
        developerMode: () -> StateFlow<Boolean>,
        wipePending: WipePendingStore,
        scope: CoroutineScope,
        installer: (LogSink) -> Boolean = PcLog::install,
        once: AtomicBoolean = ONCE,
        setDiagnostic: (Boolean) -> Unit = PcLog::setDiagnostic,
        sinkFactory: (File) -> PhoneLogSink = { PhoneLogSink.create(it) },
        wipeTimeoutMillis: Long = 5_000,
    ): PhoneLogInstall? {
        if (!once.compareAndSet(false, true)) return null
        val sink = sinkFactory(filesDir)
        val wiper = LogWiper(sink, wipePending, wipeTimeoutMillis)
        val ready = scope.launch {
            val flow = try { developerMode() } catch (_: Throwable) { null }
            if (flow != null) try { setDiagnostic(flow.value) } catch (_: Throwable) { }      // BEFORE the ring drains
            val installed = try { installer(sink) } catch (_: Throwable) { false }
            if (!installed) { sink.close(); once.set(false); return@launch }
            if (flow != null) scope.launch { try { flow.collect { setDiagnostic(it) } } catch (_: Throwable) { } }
            try { wiper.retryPending() } catch (_: Throwable) { }
        }
        return PhoneLogInstall(sink, wiper, ready)
    }

    /** The port 3b's canvas code logs through: gated by [PcLog], so it can never bypass the policy. */
    fun canvasPort(): LogPort = PcLog.port("canvas")
}

/** Delete all clears the diagnostic log files (both rotations) with a durable retry request. Never throws. */
class LogWipeListener(private val wiper: LogWiper) : ForgetListener {
    override suspend fun onDeleteAll() { withContext(Dispatchers.IO) { wiper.wipe() } }
}
