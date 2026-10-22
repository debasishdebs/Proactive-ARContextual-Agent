package com.rokid.context.memory.forget

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Slice 3a (S58): the seam through which `:services:memory` tells the layers above it that data was deleted, so files and devices (which memory does not own) follow.
 * [ForgetService] and retention's `expirePhotos` call [onFramesForgotten] AFTER their transaction committed; a rolled-back transaction notifies nothing.
 * [onRangeForgotten] and [onDeleteAll] are called by the coordinator that performed them. Implementations must be idempotent and must not throw (a throwing
 * listener is swallowed so it can never undo or block a deletion that already committed). Memory has no dependency on orchestration: this interface is the whole contract.
 */
interface ForgetListener {
    suspend fun onFramesForgotten(frameIds: List<String>) {}
    suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) {}
    suspend fun onDeleteAll() {}
}

/** The registered listeners (process-wide). */
object ForgetListeners {
    private val all = CopyOnWriteArrayList<ForgetListener>()
    fun register(l: ForgetListener) { if (l !in all) all += l }
    fun unregister(l: ForgetListener) { all -= l }
    fun clearForTests() = all.clear()
    val registered: List<ForgetListener> get() = all.toList()

    suspend fun framesForgotten(ids: List<String>) = each { it.onFramesForgotten(ids) }
    suspend fun rangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) = each { it.onRangeForgotten(fromMs, toMs, traceIds) }
    suspend fun deleteAll() = each { it.onDeleteAll() }

    private suspend fun each(f: suspend (ForgetListener) -> Unit) {
        for (l in all) try { f(l) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* a listener never undoes a committed deletion */ }
    }
}
