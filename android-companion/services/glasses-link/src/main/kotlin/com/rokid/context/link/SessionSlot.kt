package com.rokid.context.link

import com.rokid.context.core.log.PcLog
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Holds the one live SDK session (Phase 8 C4). Every path that drops a session (replaced, a failed connect, a close
 * callback, a setup throw) closes it exactly once; a [close] throw is swallowed and logged by class name. Sessions the
 * slot has already closed are remembered weakly, so a late [fail] or [clearIf] for one never closes it again.
 */
internal class SessionSlot<S : Any>(
    private val close: (S) -> Unit,
    private val log: (String) -> Unit = { PcLog.w(CxrGlassesLink.TAG, it) },
) {
    private val closed: MutableSet<S> = Collections.newSetFromMap(WeakHashMap())

    @Volatile var current: S? = null
        private set

    /** Makes [new] current (null: none), closing the previous session first. */
    fun replace(new: S?) {
        val old = synchronized(this) { current.also { current = new } }
        if (old != null && old !== new) closeOnce(old)
    }

    /** Drops [s] if it is still current (connect refused, closed by the SDK). False, and nothing closed, when stale. */
    fun clearIf(s: S): Boolean {
        val wasCurrent = synchronized(this) { (current === s).also { if (it) current = null } }
        if (wasCurrent) closeOnce(s)
        return wasCurrent
    }

    /** [s] failed during setup: closed now (once) whether or not it was made current yet. */
    fun fail(s: S) {
        synchronized(this) { if (current === s) current = null }
        closeOnce(s)
    }

    private fun closeOnce(s: S) {
        if (!synchronized(this) { closed.add(s) }) return
        try {
            close(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("session close failed (${e.javaClass.simpleName})")
        }
    }
}
