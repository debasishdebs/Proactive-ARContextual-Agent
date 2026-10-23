package com.rokid.context.phone.canvas.helper

/** In-memory [GrantStore] for JVM tests (the real one is the `canvas_helper_grants` prefs file, tested in HelperGrantsTest). */
class MemoryGrantStore : GrantStore {
    val values = mutableMapOf<String, Boolean>()
    var failWrites = false
    override fun read(key: String): Boolean = values[key] ?: false
    override fun write(key: String, on: Boolean): Boolean { if (failWrites) return false; values[key] = on; return true }
    override fun clear(): Boolean { values.clear(); return true }
}
