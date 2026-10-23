package com.rokid.context.phone.log

import android.content.Context
import com.rokid.context.core.log.LogPort
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.forget.ForgetListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Durable "a log wipe was requested and not yet acknowledged" flag (owned by the sink owner, not by the log path). */
interface WipePendingStore {
    /** The persisted value, or null when it cannot be read: an unreadable state is NEVER taken as "not pending". */
    fun read(): Boolean?
    /** True only when the new value is durably persisted (SharedPreferences `commit()` result). */
    fun write(pending: Boolean): Boolean
}

/** SharedPreferences-backed [WipePendingStore]; prefs are opened lazily so constructing it never touches disk. */
class PrefsWipePending(context: Context) : WipePendingStore {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    override fun read(): Boolean? = try { prefs.getBoolean(KEY, false) } catch (_: Throwable) { null }
    override fun write(pending: Boolean): Boolean = try { prefs.edit().putBoolean(KEY, pending).commit() } catch (_: Throwable) { false }
    companion object { const val PREFS = "log_wipe_state"; const val KEY = "wipe_pending" }
}

/**
 * Clears the diagnostic log with a durable pending request. The WHOLE transaction (read/set pending, clear, acknowledgement, clear pending) runs under
 * one lock, so a startup retry and a Delete all can never interleave and one request can never clear another's marker. A request that could not be
 * persisted (commit false or exception, retried [PERSIST_ATTEMPTS] times) is kept in memory and retried in this process; an unreadable store counts as pending.
 */
class LogWiper(private val wipeOp: (Long) -> Boolean, private val store: WipePendingStore, private val timeoutMillis: Long = 5_000) {
    constructor(sink: PhoneLogSink, store: WipePendingStore, timeoutMillis: Long = 5_000) : this(sink::wipe, store, timeoutMillis)

    private val lock = Any()
    private var inMemoryPending = false     // guarded by lock: a request that could not be persisted

    private fun persist(v: Boolean): Boolean { repeat(PERSIST_ATTEMPTS) { if (try { store.write(v) } catch (_: Throwable) { false }) return true }; return false }

    /** Records a request without wiping (used when the install barrier is not reached); the next start or [retryPending] performs it. */
    fun markPending() { synchronized(lock) { if (!persist(true)) inMemoryPending = true } }

    /** True when the log was cleared and acknowledged. A request that could not be persisted and whose wipe failed returns false and stays pending in memory. */
    fun wipe(): Boolean = synchronized(lock) {
        val persisted = persist(true)
        if (!persisted) inMemoryPending = true
        val ok = try { wipeOp(timeoutMillis) } catch (_: Throwable) { false }
        if (ok) { inMemoryPending = false; persist(false) }       // a failed clear leaves the flag set: harmless, the next start wipes again
        ok
    }

    /** At the next process start (same install path): finishes a wipe whose acknowledgement never arrived. */
    fun retryPending() {
        val due = synchronized(lock) { inMemoryPending || (try { store.read() } catch (_: Throwable) { null }) != false }
        if (due) wipe()
    }

    companion object { const val PERSIST_ATTEMPTS = 3 }
}

enum class InstallResult { PENDING, SUCCESS, REFUSED, CANCELLED }

/** [result] is final once [ready] has completed. */
class PhoneLogInstall(val sink: PhoneLogSink, val wiper: LogWiper) {
    lateinit var ready: Job
        internal set
    @Volatile var result: InstallResult = InstallResult.PENDING
        internal set
}

/** Process-start wiring of the phone logger (called from `ContextApp.onCreate` before any other component logs). */
object PhoneLogging {
    private val ONCE = AtomicBoolean(false)

    /**
     * Installs the [PhoneLogSink] into [PcLog] exactly once per process (later calls return null). Nothing here touches the filesystem on the
     * calling thread: [filesDir] must be a side-effect-free path, the writer creates the directory. On [scope] (an IO dispatcher): the persisted
     * Developer mode value is loaded and applied with [setDiagnostic] BEFORE [installer] drains PcLog's bootstrap ring, then the sink is installed,
     * a pending wipe is retried, and the flag is followed. [PhoneLogInstall.result] says what happened; unless it is SUCCESS (refused, or the scope
     * was cancelled before or during the install) the writer is closed and the once-guard re-armed. The logging path only reads PcLog's volatile snapshot.
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
        val handle = PhoneLogInstall(sink, wiper)
        val ready = scope.launch {
            try {
                val flow = try { developerMode() } catch (_: Throwable) { null }
                ensureActive()
                if (flow != null) try { setDiagnostic(flow.value) } catch (_: Throwable) { }      // BEFORE the ring drains
                val installed = try { installer(sink) } catch (_: Throwable) { false }
                if (!installed) { handle.result = InstallResult.REFUSED; return@launch }
                handle.result = InstallResult.SUCCESS
                if (flow != null) scope.launch { try { flow.collect { setDiagnostic(it) } } catch (_: Throwable) { } }
                try { wiper.retryPending() } catch (_: Throwable) { }
            } catch (e: CancellationException) {
                if (handle.result != InstallResult.SUCCESS) handle.result = InstallResult.CANCELLED
                throw e
            }
        }
        handle.ready = ready
        ready.invokeOnCompletion {
            if (handle.result == InstallResult.PENDING) handle.result = InstallResult.CANCELLED      // never started (scope cancelled first)
            if (handle.result != InstallResult.SUCCESS) { sink.close(); once.set(false) }
        }
        return handle
    }

    /** The port 3b's canvas code logs through: gated by [PcLog], so it can never bypass the policy. */
    fun canvasPort(): LogPort = PcLog.port("canvas")
}

/**
 * Delete all clears the diagnostic log files (both rotations) with a durable retry request, but only after the install barrier [ready]
 * (so the bootstrap ring's replay can never undo an acknowledged wipe). Never throws.
 * ACCEPTED GAP: MemoryWipe clears its own coordinator marker BEFORE invoking listeners, so a process death in that tiny window leaves the old counts-only logs.
 */
class LogWipeListener(private val wiper: LogWiper, private val ready: Job? = null, private val barrierMillis: Long = 10_000) : ForgetListener {
    override suspend fun onDeleteAll() {
        withContext(Dispatchers.IO) {
            val reached = ready == null || ready.isCompleted || withTimeoutOrNull(barrierMillis) { ready.join(); true } == true
            if (reached) wiper.wipe() else wiper.markPending()      // not installed yet: the start-up retry performs it right after the replay
        }
    }
}
