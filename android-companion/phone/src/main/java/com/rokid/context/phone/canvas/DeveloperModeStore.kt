package com.rokid.context.phone.canvas

import android.content.Context
import com.rokid.context.phone.canvas.server.CanvasAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Developer mode switch (Settings > Diagnostics). Off by default; the experiment canvas never runs while it is off. */
class DeveloperModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _sessionHours = MutableStateFlow(clamp(prefs.getInt(KEY_SESSION_HOURS, 0)))
    /** Roadmap row 7: how many hours a canvas pairing made from now on lasts, also across a restart of the app (0 = the normal 30 min idle / 12 h rules). Only one of [SESSION_HOURS]. */
    val sessionHours: StateFlow<Int> = _sessionHours.asStateFlow()

    /** The lifetime a session paired now may have: 0 unless Developer mode is on AND a lifetime was chosen. */
    fun sessionLifetimeMs(): Long = if (_enabled.value) _sessionHours.value * 3_600_000L else 0L

    /** Stores the choice (anything not in [SESSION_HOURS] is Off). The caller revokes every session so the new rule starts clean. */
    fun setSessionHours(hours: Int) {
        val h = clamp(hours)
        prefs.edit().putInt(KEY_SESSION_HOURS, h).apply()
        _sessionHours.value = h
        if (h == 0) writeSessions(emptyList())
    }

    /** The longer sessions' digests, in this (per-device, backup-excluded) file; the token is never stored. */
    val vault: CanvasAuth.SessionVault = object : CanvasAuth.SessionVault {
        override fun read(): List<CanvasAuth.StoredSession> =
            (prefs.getString(KEY_SESSIONS, null) ?: "").split(';').mapNotNull { e ->
                val f = e.split(',')
                if (f.size != 3) null else CanvasAuth.StoredSession(f[0], f[1].toLongOrNull() ?: return@mapNotNull null, f[2].toLongOrNull() ?: return@mapNotNull null)
            }
        override fun write(sessions: List<CanvasAuth.StoredSession>) = writeSessions(sessions)
    }

    private fun writeSessions(sessions: List<CanvasAuth.StoredSession>) {
        prefs.edit().apply { if (sessions.isEmpty()) remove(KEY_SESSIONS) else putString(KEY_SESSIONS, sessions.joinToString(";") { "${it.digestHex},${it.createdAt},${it.lifeMs}" }) }.apply()
    }

    private val _signalTry = MutableStateFlow(prefs.getBoolean(KEY_SIGNAL_TRY, false))
    /** v1.2 row 2: the derived-signal "try it on recent data" preview. OFF by default; it also needs Developer mode (the route checks both, on every call). */
    val signalTryEnabled: StateFlow<Boolean> = _signalTry.asStateFlow()
    fun setSignalTry(on: Boolean) { prefs.edit().putBoolean(KEY_SIGNAL_TRY, on).apply(); _signalTry.value = on }

    private val _detailedLog = MutableStateFlow(prefs.getBoolean(KEY_DETAILED_LOG, true))
    /** Trip build: the full diagnostic log (every I and D line, not only milestones and warnings) is ON by default; the user can turn it off in Settings > Diagnostics. Lives in this per-device, backup-excluded file. */
    val detailedLog: StateFlow<Boolean> = _detailedLog.asStateFlow()

    private val _diagnosticLog = MutableStateFlow(_enabled.value || _detailedLog.value)
    /** What PcLog's diagnostic snapshot follows: the detailed log switch, or Developer mode (which always turned diagnostics on). */
    val diagnosticLog: StateFlow<Boolean> = _diagnosticLog.asStateFlow()

    fun setDetailedLog(on: Boolean) {
        prefs.edit().putBoolean(KEY_DETAILED_LOG, on).apply()
        _detailedLog.value = on
        _diagnosticLog.value = _enabled.value || on
    }

    fun set(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
        _diagnosticLog.value = on || _detailedLog.value
    }

    companion object {
        const val PREFS = "pc_dev"
        const val KEY = "developer_mode"
        const val KEY_DETAILED_LOG = "detailed_log"
        const val KEY_SIGNAL_TRY = "signal_try_enabled"
        const val KEY_SESSION_HOURS = "canvas_session_hours"
        const val KEY_SESSIONS = "canvas_sessions"
        /** The lifetimes a developer can choose: Off, 1, 3 or 7 days (7 days is the hard maximum, see `CanvasAuth.MAX_DEV_LIFETIME_MS`). */
        val SESSION_HOURS = listOf(0, 24, 72, 168)
        private fun clamp(h: Int) = if (h in SESSION_HOURS) h else 0
        @Volatile private var instance: DeveloperModeStore? = null

        /** One store per process, so every screen and the graph see the same flow. */
        fun get(context: Context): DeveloperModeStore = instance ?: synchronized(this) { instance ?: DeveloperModeStore(context).also { instance = it } }
    }
}
