package com.rokid.context.phone.nexus

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.model.HudHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** v2.0 T5/T6: the three Developer-visible Nexus switches. ALL OFF by default (ruling B-Q4: no Nexus STT/TTS, no assist takeover, no skills until the user turns them on). */
data class NexusSwitches(
    /** "Nexus voice (assist button)": Nexus speech to text plus the assist-button takeover. */
    val voice: Boolean = false,
    /** "Speak answers (TTS)". */
    val tts: Boolean = false,
    /** "Skills": the four action-only skills. */
    val skills: Boolean = false,
)

/**
 * v2.0 T7: who draws the HUD popups and cards. PCA's own glasses app is the default and Rokid Nexus is an explicit opt-in (Settings > Rokid Nexus, Developer-visible). The three switches ([NexusSwitches]) live in the SAME file (keys `voice`, `tts`, `skills`; booleans, default false). Besides them only the flag `wipe_owed` is stored here (a history wipe the hub has not acknowledged yet; no card text, no ids): the prefs file `nexus_host` is excluded from backup and transfer (BackupRulesTest) and kept by Delete all (DeletionGraph). Anything that is not exactly "NEXUS" reads as PCA.
 */
class NexusHostStore(private val prefs: SharedPreferences) : WipeDebt {
    private val _host = MutableStateFlow(read())
    val host: StateFlow<HudHost> = _host

    private fun read(): HudHost = if (prefs.getString(KEY, null) == HudHost.NEXUS.name) HudHost.NEXUS else HudHost.PCA

    fun current(): HudHost = _host.value

    private val _switches = MutableStateFlow(readSwitches())
    val switches: StateFlow<NexusSwitches> = _switches

    private fun on(key: String): Boolean = try { prefs.getBoolean(key, false) } catch (_: ClassCastException) { false } // anything but a stored `true` is off
    private fun readSwitches() = NexusSwitches(on(K_VOICE), on(K_TTS), on(K_SKILLS))

    fun switchesNow(): NexusSwitches = _switches.value

    /** True when the switches changed. Committed synchronously (a privacy-relevant opt-in must survive a crash). */
    fun setSwitches(s: NexusSwitches): Boolean {
        if (_switches.value == s) return false
        prefs.edit().putBoolean(K_VOICE, s.voice).putBoolean(K_TTS, s.tts).putBoolean(K_SKILLS, s.skills).commit()
        _switches.value = s
        return true
    }

    // ---- the history wipe PCA owes the Nexus hub (Codex v2 r1 P1-5) ----

    /** A history wipe is owed (Forget, Delete all or mode off ran while it could not be delivered, or the hub has not acknowledged it). A stored flag only: no text, no ids. */
    override fun wipeOwed(): Boolean = try { prefs.getBoolean(K_WIPE, false) } catch (_: ClassCastException) { true } // an unreadable flag counts as owed (fail closed)

    /** Committed synchronously: the debt must survive a crash or a process kill right after the trip. */
    override fun owe() { if (!wipeOwed()) prefs.edit().putBoolean(K_WIPE, true).commit() }

    override fun settle() { if (wipeOwed()) prefs.edit().remove(K_WIPE).commit() }

    /** True when the choice changed. Committed synchronously: a host switch is a privacy-relevant switch and must survive a crash right after it. */
    fun set(h: HudHost): Boolean {
        if (_host.value == h) return false
        prefs.edit().putString(KEY, h.name).commit()
        _host.value = h
        return true
    }

    companion object {
        /** The prefs file (not backed up). */
        const val PREFS = "nexus_host"
        private const val KEY = "host"
        private const val K_VOICE = "voice"
        private const val K_TTS = "tts"
        private const val K_SKILLS = "skills"
        private const val K_WIPE = "wipe_owed"

        @Volatile private var shared: NexusHostStore? = null
        fun of(context: Context): NexusHostStore = shared ?: synchronized(this) {
            shared ?: NexusHostStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { shared = it }
        }
        internal fun resetForTests() { shared = null }
    }
}
