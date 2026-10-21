package com.rokid.context.work.frames.scene

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/** The user's list of things worth a photo ("keys", "receipt"); stored locally, edited in Memory settings. */
class WatchListStore(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    /** Trimmed, distinct (case-insensitive), 1..20 items of at most 40 characters. */
    val items: StateFlow<List<String>> = state.asStateFlow()

    fun set(items: List<String>) {
        val clean = clean(items)
        if (clean.isEmpty()) { reset(); return }
        prefs.edit().putString(KEY, clean.joinToString("\n")).apply()
        state.value = clean
    }

    fun reset() {
        prefs.edit().remove(KEY).apply()
        state.value = DEFAULTS
    }

    private fun read(): List<String> =
        prefs.getString(KEY, null)?.let { clean(it.split('\n')) }?.takeIf { it.isNotEmpty() } ?: DEFAULTS

    companion object {
        private const val KEY = "watch_items"
        const val MAX_ITEMS = 20
        const val MAX_LEN = 40
        val DEFAULTS = listOf("keys", "car", "parking sign", "receipt", "whiteboard", "document", "a person's face")

        internal fun clean(items: List<String>): List<String> =
            items.map { it.trim().replace(Regex("\\s+"), " ").take(MAX_LEN).trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(MAX_ITEMS)
    }
}

/** Query vectors for the list, cached per (space fingerprint, items, dims); rebuilt when either changes. */
class WatchVectors(private val store: WatchListStore) {
    private data class Key(val fingerprint: String, val items: List<String>, val dims: Int)

    private val lock = Mutex()
    private var cachedKey: Key? = null
    private var cached: List<FloatArray> = emptyList()

    /** Embeds each item at full width, keeps the first [dims] values and re-normalises to unit length. */
    suspend fun vectors(fingerprint: String, dims: Int, embedQueryFull: suspend (String) -> FloatArray): List<FloatArray> =
        lock.withLock {
            val key = Key(fingerprint, store.items.value, dims)
            if (key == cachedKey) return cached
            val built = key.items.map { shorten(embedQueryFull(it), dims) }
            cachedKey = key
            cached = built
            built
        }

    /** The best cosine of [v] against [list], or null when the list is empty. */
    fun maxCos(v: FloatArray, list: List<FloatArray>): Float? {
        if (list.isEmpty()) return null
        val nv = norm(v)
        if (nv == 0f) return 0f
        return list.maxOf { w ->
            val n = minOf(v.size, w.size)
            var dot = 0f
            for (i in 0 until n) dot += v[i] * w[i]
            val nw = norm(w)
            if (nw == 0f) 0f else dot / (nv * nw)
        }
    }

    private fun shorten(full: FloatArray, dims: Int): FloatArray {
        val head = full.copyOf(minOf(dims, full.size))
        val n = norm(head)
        return if (n == 0f) head else FloatArray(head.size) { head[it] / n }
    }

    private fun norm(v: FloatArray): Float {
        var s = 0.0
        for (x in v) s += x * x
        return sqrt(s).toFloat()
    }
}
