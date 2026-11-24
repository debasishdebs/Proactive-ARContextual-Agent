package com.rokid.context.memory.forget

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Slice 3a (S58): the seam through which `:services:memory` tells the layers above it that data was deleted, so files and devices (which memory does not own) follow.
 * [ForgetService] calls [onFramesForgotten] AFTER a user Forget committed; a rolled-back transaction notifies nothing. Retention expiry (photos, legacy frames, memories) is storage hygiene and notifies NO listener (retention r1).
 * [onRangeForgotten] and [onDeleteAll] are called by the coordinator that performed them. Implementations must be idempotent and must not throw (a throwing
 * listener is swallowed so it can never undo or block a deletion that already committed). Memory has no dependency on orchestration: this interface is the whole contract.
 */
interface ForgetListener {
    suspend fun onFramesForgotten(frameIds: List<String>) {}
    suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) {}
    suspend fun onDeleteAll() {}
    /**
     * RETENTION expiry of photos COMMITTED (the photo files go; everything described about them stays). Defaults to [onFramesForgotten], so every existing listener behaves as before.
     * A listener whose data must outlive the photo (the place card history keeps its rows and only drops the photo link) overrides it.
     */
    suspend fun onPhotosExpired(frameIds: List<String>) = onFramesForgotten(frameIds)
    /** The time link of a retention expiry (marks and traces within the radius of an expired photo). Defaults to [onRangeForgotten]; a listener that is not touched by routine retention overrides it. */
    suspend fun onRangeExpired(fromMs: Long, toMs: Long, traceIds: List<String>) = onRangeForgotten(fromMs, toMs, traceIds)
    /** A user Forget of any kind (a fact, a photo, a moment, a mark, a multi-select, a scoped range) COMMITTED. Carries no ids: a listener that must withdraw what it shows only needs to know it happened (Nexus bridge, v2.0). Not called for retention expiry. */
    suspend fun onUserForget() {}
}

/** The registered listeners (process-wide). */
object ForgetListeners {
    private val all = CopyOnWriteArrayList<ForgetListener>()
    fun register(l: ForgetListener) { if (l !in all) all += l }
    fun unregister(l: ForgetListener) { all -= l }
    fun clearForTests() = all.clear()
    val registered: List<ForgetListener> get() = all.toList()

    suspend fun framesForgotten(ids: List<String>) = each { it.onFramesForgotten(ids) }
    suspend fun photosExpired(ids: List<String>) = each { it.onPhotosExpired(ids) }
    suspend fun rangeExpired(fromMs: Long, toMs: Long, traceIds: List<String>) = each { it.onRangeExpired(fromMs, toMs, traceIds) }
    suspend fun rangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>, except: ForgetListener? = null) = each(except) { it.onRangeForgotten(fromMs, toMs, traceIds) }
    suspend fun deleteAll() = each { it.onDeleteAll() }
    suspend fun userForget() = each { it.onUserForget() }

    private suspend fun each(except: ForgetListener? = null, f: suspend (ForgetListener) -> Unit) {
        for (l in all) if (l !== except) try { f(l) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* a listener never undoes a committed deletion */ }
    }
}
