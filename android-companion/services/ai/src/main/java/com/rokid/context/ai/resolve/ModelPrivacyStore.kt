package com.rokid.context.ai.resolve

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.ModelPrivacy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings > Capture & privacy > Model privacy, in one app-private prefs file ([PREFS]) plus a second one ([SENTINEL_PREFS]) that only says "the user has chosen a mode at least once". Both are per-device: excluded from
 * backup and device transfer like the neighbouring Data saver and Place Guide switches (AUTO again after a restore, which is a fresh install: neither file exists). Delete all leaves them.
 *
 * Fail closed. The mode is AUTO only when it was TRULY never set (neither file knows of a choice). A value that is not a known mode, a read that throws, or a mode key that is missing although a choice was recorded
 * (a corrupted or emptied `ai_backend.xml` for a user who had Offline only) reads as [ModelPrivacy.OFFLINE_ONLY], and [wasReset] is true so the screen can say so until the user picks a mode again.
 *
 * The in-memory value is authoritative after the first load (so a tap needs no disk wait and every reader agrees); [set] writes asynchronously (`apply`). The first load reads the file: warm it off the main thread ([warm]).
 */
class ModelPrivacyStore(
    private val prefs: SharedPreferences, private val sentinel: SharedPreferences = prefs,
    /** Runs the durable write. Production: one background thread (in order, `commit()` so a killed process cannot bring back an older value); tests may pass a direct executor. */
    private val writer: java.util.concurrent.Executor = DEFAULT_WRITER,
) {
    private class Loaded(val mode: ModelPrivacy, val reset: Boolean)
    @Volatile private var loaded: Loaded? = null
    private val state = MutableStateFlow(ModelPrivacy.DEFAULT)
    private val resetState = MutableStateFlow(false)

    private fun load(): Loaded {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val l = try {
                val m = prefs.getString(K_MODE, null)
                val last = sentinel.getString(K_LAST, null) // the intended mode, kept in the SEPARATE file: a main file that failed to take "Offline only" must not read back as Automatic
                when {
                    m == ModelPrivacy.AUTO.name && last == ModelPrivacy.OFFLINE_ONLY.name -> Loaded(ModelPrivacy.OFFLINE_ONLY, true)
                    m != null -> Loaded(ModelPrivacy.parse(m), ModelPrivacy.entries.none { it.name == m }) // a value this build does not know is "reset" too: Offline only until the user chooses
                    chosenBefore() -> Loaded(ModelPrivacy.OFFLINE_ONLY, true)
                    else -> Loaded(ModelPrivacy.DEFAULT, false)
                }
            } catch (e: Exception) { Loaded(ModelPrivacy.OFFLINE_ONLY, true) }
            loaded = l; state.value = l.mode; resetState.value = l.reset
            return l
        }
    }

    private fun chosenBefore(): Boolean = prefs.getBoolean(K_INIT, false) || sentinel.getBoolean(K_INIT, false)

    /** The mode right now. Cheap after the first call. */
    fun current(): ModelPrivacy = load().mode

    /** The mode as a flow for screens (first read happens at construction of a collector; warm the store first to keep it off the main thread). */
    val mode: StateFlow<ModelPrivacy> get() { load(); return state.asStateFlow() }

    /** True when the stored choice was lost (the file was emptied or corrupted although a mode had been set): the mode then reads Offline only and the screen asks the user to check it. */
    val wasReset: StateFlow<Boolean> get() { load(); return resetState.asStateFlow() }

    /** The loaded value, or null before the first load (never reads the disk): for a screen's first frame. */
    fun peek(): ModelPrivacy? = loaded?.mode

    /** Forces the first load (call from an IO thread at start-up). */
    fun warm() { load() }

    private val writeLock = Any()
    @Volatile private var version = 0L

    /**
     * Step 1, at once (in memory, queued to disk by `apply`): the sentinel and "initialised" are written and the stored mode key is REMOVED. If the process dies, or a later write fails, before the new mode reaches the disk, the next
     * launch finds "a choice was made but the mode is missing" and reads Offline only with the reset notice. It can never read the old, possibly more permissive, value or Automatic by accident.
     */
    /** A change that has begun (the mode is already in force in memory) and still has to reach the disk: [commit] it. */
    class Pending internal constructor(internal val mode: ModelPrivacy, internal val version: Long)

    /**
     * SYNCHRONOUS and cheap (call it on the caller's thread, from the tap): from the moment it returns, [current] answers the new mode, so the router, the network guards and the tile check act on it at once,
     * whatever happens to the screen afterwards. It also writes the marker (see above). Returns the [Pending] write for [commit].
     */
    fun begin(m: ModelPrivacy): Pending = synchronized(this) {
        loaded = Loaded(m, false); state.value = m; resetState.value = false
        try {
            if (sentinel !== prefs) sentinel.edit().putString(K_LAST, m.name).putBoolean(K_INIT, true).apply()
            prefs.edit().remove(K_MODE).putBoolean(K_INIT, true).apply()
        } catch (e: Exception) { PcLog.w(TAG, "privacy begin failed") }
        Pending(m, ++version)
    }

    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** The durable write of [p], on IO, in a scope no screen can cancel (the process-wide store owns it). [onDone] gets true when it is durable. */
    fun commitAsync(p: Pending, onDone: (Boolean) -> Unit = {}) { ioScope.launch { onDone(try { durable(p.mode, p.version) } catch (e: Exception) { false }) } }

    /** Step 2: the durable write (sentinel first, then the mode), each retried once; a fixed token is logged on failure. False = the disk may still say "reset" (safe: Offline only). Superseded writes are skipped. */
    private fun durable(m: ModelPrivacy, v: Long): Boolean = synchronized(writeLock) {
        if (v != version) return@synchronized true // a newer choice owns the disk now
        try { com.rokid.context.ai.OfflineOnlyNet.evictPools() } catch (e: Exception) { }
        fun commitTwice(write: () -> Boolean): Boolean = try { write() || write() } catch (e: Exception) { false }
        val ok = (sentinel === prefs || commitTwice { sentinel.edit().putString(K_LAST, m.name).putBoolean(K_INIT, true).commit() }) &&
            commitTwice { prefs.edit().putString(K_MODE, m.name).putBoolean(K_INIT, true).commit() }
        if (!ok) PcLog.w(TAG, "privacy write failed")
        ok
    }

    /** Sets the mode: in memory at once, durable in the background (a failed or killed write reads back as "reset", never as Automatic). */
    fun set(m: ModelPrivacy) { val p = begin(m); writer.execute { durable(p.mode, p.version) } }

    /** Like [set] but returns only after the write was attempted on [writer-independent] IO: true = durable. The UI shows "Saving" meanwhile; switching TO Offline only should use this. */
    suspend fun setAndWait(m: ModelPrivacy): Boolean { val p = begin(m); return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.NonCancellable) { durable(p.mode, p.version) } }

    companion object {
        private const val TAG = "PcPrivacy"
        const val PREFS = "ai_backend"
        const val SENTINEL_PREFS = "ai_backend_init"
        private const val K_MODE = "mode"
        private const val K_INIT = "initialised"
        private const val K_LAST = "last_choice"
        private val DEFAULT_WRITER: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "model-privacy-write").also { it.isDaemon = true } }
        @Volatile private var instance: ModelPrivacyStore? = null

        /** The process-wide store, so every reader and the screen share one value. */
        fun get(context: Context): ModelPrivacyStore = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: ModelPrivacyStore(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), app.getSharedPreferences(SENTINEL_PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }

        fun resetForTests(store: ModelPrivacyStore?) { synchronized(this) { instance = store } }
    }
}
