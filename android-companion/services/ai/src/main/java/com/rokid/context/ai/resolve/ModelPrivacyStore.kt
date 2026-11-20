package com.rokid.context.ai.resolve

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.net.ModelPrivacy
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
                when {
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

    fun set(m: ModelPrivacy) {
        synchronized(this) {
            loaded = Loaded(m, false); state.value = m; resetState.value = false
        }
        // The in-memory value is authoritative from here; the write is durable (commit, in order, off the caller's thread). Open connections of the model clients are dropped so a new mode never rides an old connection.
        writer.execute {
            try { com.rokid.context.ai.OfflineOnlyNet.evictPools() } catch (e: Exception) { }
            try {
                prefs.edit().putString(K_MODE, m.name).putBoolean(K_INIT, true).commit()
                if (sentinel !== prefs) sentinel.edit().putBoolean(K_INIT, true).commit()
            } catch (e: Exception) { /* the in-memory value still holds for this process */ }
        }
    }

    companion object {
        const val PREFS = "ai_backend"
        const val SENTINEL_PREFS = "ai_backend_init"
        private const val K_MODE = "mode"
        private const val K_INIT = "initialised"
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
