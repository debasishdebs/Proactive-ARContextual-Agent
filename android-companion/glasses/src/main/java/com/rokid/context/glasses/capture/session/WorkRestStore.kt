package com.rokid.context.glasses.capture.session

import android.content.SharedPreferences

/** The work/rest cycle on the [WorkRest] clock (elapsedRealtime), as of [savedAtElapsedMs], on boot [bootCount] (-1 = unknown). */
data class WorkRestSnapshot(
    val workStartMs: Long?, val pausedAtMs: Long?, val restUntilMs: Long?,
    val savedAtElapsedMs: Long, val bootCount: Int = -1,
)

/** Keeps the work/rest cycle across process death (a Rokid force-stop must not restart a rest as a full work period). */
interface WorkRestStore {
    fun load(): WorkRestSnapshot?
    fun save(s: WorkRestSnapshot)
    fun clear()

    object None : WorkRestStore {
        override fun load(): WorkRestSnapshot? = null
        override fun save(s: WorkRestSnapshot) {}
        override fun clear() {}
    }
}

/** Keys ws, pa, ru, at (Long; absent = null) and bc (Int). Always apply(), never commit(). */
class PrefsWorkRestStore(private val prefs: SharedPreferences) : WorkRestStore {
    override fun load(): WorkRestSnapshot? {
        if (!prefs.contains(AT)) return null
        fun l(k: String) = if (prefs.contains(k)) prefs.getLong(k, 0L) else null
        return WorkRestSnapshot(l(WS), l(PA), l(RU), prefs.getLong(AT, 0L), prefs.getInt(BC, -1))
    }

    override fun save(s: WorkRestSnapshot) {
        val e = prefs.edit()
        fun put(k: String, v: Long?) { if (v == null) e.remove(k) else e.putLong(k, v) }
        put(WS, s.workStartMs); put(PA, s.pausedAtMs); put(RU, s.restUntilMs)
        e.putLong(AT, s.savedAtElapsedMs).putInt(BC, s.bootCount).apply()
    }

    override fun clear() { prefs.edit().clear().apply() }

    private companion object { const val WS = "ws"; const val PA = "pa"; const val RU = "ru"; const val AT = "at"; const val BC = "bc" }
}
