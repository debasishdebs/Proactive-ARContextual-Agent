package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.ai.canvas.plan.DerivedSignal
import com.rokid.context.ai.canvas.plan.DerivedSignals
import com.rokid.context.ai.canvas.plan.SignalConfig
import com.rokid.context.capture.plan.SignalCatalog
import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The signal configuration (v0.9.7 A): which catalogue signals guards may use, and the user's derived signals. Prefs file [PREFS] (a per-device compile default like the window
 * tolerance; excluded from backup like the canvas DB whose guards use it). Every write is validated by the guard/value validator ([DerivedSignals.validate]); an invalid stored
 * value is dropped at load. Logging by event name only. With no prefs it lives in memory.
 */
class SignalConfigStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(load())
    val config: StateFlow<SignalConfig> = state.asStateFlow()

    private fun load(): SignalConfig {
        val disabled = runCatching { (Json.parseToJsonElement(prefs?.getString(DISABLED, null) ?: "[]") as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }
            .getOrDefault(emptyList()).mapNotNull { SignalCatalog.canonical(it) }.toSet()
        val raw = runCatching { Json.parseToJsonElement(prefs?.getString(DERIVED, null) ?: "{}") as JsonObject }.getOrDefault(JsonObject(emptyMap()))
        val kept = ArrayList<DerivedSignal>()
        for ((name, g) in raw) {
            val gg = g as? JsonObject ?: continue
            if (DerivedSignals.validate(name, gg, SignalConfig(derived = kept)).isEmpty()) kept += DerivedSignal(name, gg)
        }
        return SignalConfig(disabled, kept)
    }

    private fun persist(c: SignalConfig) {
        prefs?.edit()?.putString(DISABLED, JsonArray(c.disabled.sorted().map { JsonPrimitive(it) }).toString())
            ?.putString(DERIVED, JsonObject(c.derived.associate { it.name to it.graph }).toString())?.apply()
        state.value = c
    }

    /** Saves (or replaces) a derived signal; the returned problems are empty when it was saved. */
    @Synchronized fun saveDerived(name: String, graph: JsonElement?): List<String> {
        val problems = DerivedSignals.validate(name, graph, state.value)
        if (problems.isNotEmpty()) { PcLog.w(TAG, "signal config rejected"); return problems }
        val c = state.value
        persist(c.copy(derived = c.derived.filter { it.name != name } + DerivedSignal(name, graph as JsonObject)))
        PcLog.i(TAG, "signal config saved")
        return emptyList()
    }

    @Synchronized fun deleteDerived(name: String) {
        val c = state.value
        if (c.derived.none { it.name == name }) return
        persist(c.copy(derived = c.derived.filter { it.name != name }))
        PcLog.i(TAG, "signal config saved")
    }

    /** Turns a catalogue signal (or its alias) on or off for guards; false for a name that is not in the catalogue. */
    @Synchronized fun setEnabled(signal: String, on: Boolean): Boolean {
        val canonical = SignalCatalog.canonical(signal) ?: run { PcLog.w(TAG, "signal config rejected"); return false }
        val c = state.value
        persist(c.copy(disabled = if (on) c.disabled - canonical else c.disabled + canonical))
        PcLog.i(TAG, "signal config saved")
        return true
    }

    @Synchronized fun clear() { persist(SignalConfig()); PcLog.i(TAG, "signal config saved") }

    companion object {
        const val PREFS = "signal_config"
        private const val DISABLED = "disabled"
        private const val DERIVED = "derived"
        private const val TAG = "SignalConfig"

        @Volatile private var instance: SignalConfigStore? = null
        fun get(context: Context): SignalConfigStore = instance ?: synchronized(this) {
            instance ?: SignalConfigStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
