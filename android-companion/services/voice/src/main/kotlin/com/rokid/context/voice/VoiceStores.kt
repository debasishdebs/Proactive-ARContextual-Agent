package com.rokid.context.voice

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The last probe verdict for one OS build + app version + language; [fromGuard] = set by the production guard. */
data class ProbeRecord(val fingerprint: String, val verdict: ProbeVerdict, val fromGuard: Boolean = false)

interface ProbeRecords {
    suspend fun read(): ProbeRecord?
    suspend fun write(r: ProbeRecord)
}

class InMemoryProbeRecords : ProbeRecords {
    @Volatile private var record: ProbeRecord? = null
    override suspend fun read(): ProbeRecord? = record
    override suspend fun write(r: ProbeRecord) { record = r }
}

/** SharedPreferences opened and read on [work] only (Threads constraint). */
class PrefsProbeRecords(private val prefs: () -> SharedPreferences, private val work: CoroutineDispatcher = Dispatchers.IO) : ProbeRecords {
    private val p by lazy(prefs)

    override suspend fun read(): ProbeRecord? = withContext(work) {
        val fp = p.getString(K_FP, null) ?: return@withContext null
        val verdict = runCatching { ProbeVerdict.valueOf(p.getString(K_VERDICT, null).orEmpty()) }.getOrNull() ?: return@withContext null
        ProbeRecord(fp, verdict, p.getBoolean(K_GUARD, false))
    }

    override suspend fun write(r: ProbeRecord) = withContext(work) {
        p.edit().putString(K_FP, r.fingerprint).putString(K_VERDICT, r.verdict.name).putBoolean(K_GUARD, r.fromGuard).commit()
        Unit
    }

    private companion object { const val K_FP = "fingerprint"; const val K_VERDICT = "verdict"; const val K_GUARD = "from_guard" }
}

/** Settings › Phone camera & mic values. Loaded on [work] when built (defaults until then); writes wait for the load. */
class VoiceSettingsStore(private val prefs: () -> SharedPreferences, private val scope: CoroutineScope, private val work: CoroutineDispatcher = Dispatchers.IO) {
    private val p by lazy(prefs)
    private val state = MutableStateFlow(VoiceSettings())
    val settings: StateFlow<VoiceSettings> = state.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    /** False until the stored values replaced the defaults: callers that must not act on a default can wait for it. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val writes = Mutex()
    private val load = scope.async(work) { state.value = read(); _loaded.value = true }

    fun update(f: (VoiceSettings) -> VoiceSettings): Job = scope.launch(work) {
        load.await()
        writes.withLock {
            val n = f(state.value)
            p.edit().putString(K_LANG, n.language.name).putBoolean(K_MIC, n.phoneMic).commit()
            state.value = n
        }
    }

    private fun read() = VoiceSettings(
        language = runCatching { SpeechLanguage.valueOf(p.getString(K_LANG, null).orEmpty()) }.getOrDefault(SpeechLanguage.ENGLISH),
        phoneMic = p.getBoolean(K_MIC, true),
    )

    private companion object { const val K_LANG = "language"; const val K_MIC = "phone_mic" }
}
