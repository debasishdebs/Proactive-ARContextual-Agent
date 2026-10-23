package com.rokid.context.phone.canvas.helper

import android.content.Context
import com.rokid.context.orchestration.MemoryWipe

/** Where the grant bits live. The real one is the `canvas_helper_grants` prefs file; tests use memory. */
interface GrantStore {
    fun read(key: String): Boolean
    /** True only when the new value is durably written. */
    fun write(key: String, on: Boolean): Boolean
    fun clear(): Boolean
}

internal class PrefsGrantStore(context: Context) : GrantStore {
    private val prefs by lazy { context.applicationContext.getSharedPreferences(HelperGrants.PREFS, Context.MODE_PRIVATE) }
    override fun read(key: String): Boolean = try { prefs.getBoolean(key, false) } catch (_: Throwable) { false }
    override fun write(key: String, on: Boolean): Boolean = try { prefs.edit().putBoolean(key, on).commit() } catch (_: Throwable) { false }
    override fun clear(): Boolean = try { prefs.edit().clear().commit() } catch (_: Throwable) { false }
}

/**
 * Capability grants of the laptop helper (3b WP2). One grant today, `py_exec`, default OFF. The value is read from the store every time (no cache), so a Delete all
 * that clears the prefs file ([MemoryWipe.WIPED_PREFS], DeletionGraph.PREFS) is seen at once. Only the native card ([HelperNative.setGrant]) and the session end
 * write it: [set] and [allOff] are module-internal and no HTTP route reaches them. An unknown grant name is never on and cannot be set.
 */
class HelperGrants(private val store: GrantStore) {
    companion object {
        const val PREFS = "canvas_helper_grants"
        const val PY_EXEC = "py_exec"
        val KNOWN: Set<String> = setOf(PY_EXEC)

        fun of(context: Context): HelperGrants = HelperGrants(PrefsGrantStore(context))
    }

    init { check(PREFS == MemoryWipe.HELPER_GRANTS_PREFS) }

    fun isOn(grant: String): Boolean = grant in KNOWN && store.read(grant)

    internal fun set(grant: String, on: Boolean): Boolean = grant in KNOWN && store.write(grant, on)

    internal fun allOff(): Boolean = store.clear()
}
