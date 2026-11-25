package com.rokid.context.work.frames.place

import com.rokid.context.memory.forget.ForgetListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The registry of the Place Guide shadow's IN-MEMORY derived data (1.4.4 slice B1): the pin reference image vectors ([PinReferenceVectors], derived from a photo the user can Forget) and the pack-place text
 * vectors of [PlaceShadow]. Nothing here is persisted; the registry exists so that every deletion path can empty them AT ONCE instead of waiting for the next shadow attempt (which a long-lived hub process may
 * never make):
 *  - Delete all ([com.rokid.context.orchestration.MemoryWipe.deleteAll]) and every Forget listener event (a photo, a range, any user Forget, retention expiry) through [listener];
 *  - a pin link change (set, cleared by a sweep, or a pin deleted with its Moment);
 *  - the `placeShadowScore` flag going OFF.
 * Revalidation on every use stays as the second layer (a listener can miss a trigger). [DERIVED_ITEMS] is the inventory list the Forget docs and a test pin.
 */
object ShadowCaches {
    /** The in-memory derived items, as the Forget inventory names them (in-memory derived, never stored, cleared by every deletion path). */
    val DERIVED_ITEMS = listOf("place_shadow.pin_reference_vectors", "place_shadow.pack_text_vectors", "place_shadow.pack_moment_counts")

    private val clears = ConcurrentHashMap<String, () -> Unit>()
    private val epochValue = AtomicLong(0)

    /** Registers (or replaces) the clear action under [name] (one of [DERIVED_ITEMS]). Called when the cache is first created. */
    fun register(name: String, clear: () -> Unit) { clears[name] = clear }

    /** Counts every [clearAll]. */
    fun epoch(): Long = epochValue.get()

    fun registered(): Set<String> = clears.keys.toSet()

    /** Empties every registered cache now. Never throws. */
    fun clearAll() {
        epochValue.incrementAndGet()
        for (c in clears.values) try { c() } catch (_: Exception) { }
    }

    /** Registered once at start (ContextApp): every deletion event empties the caches. Carries no ids. */
    fun listener(): ForgetListener = object : ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) = clearAll()
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) = clearAll()
        override suspend fun onDeleteAll() = clearAll()
        override suspend fun onUserForget() = clearAll()
    }

    fun resetForTests() { clears.clear() }
}
