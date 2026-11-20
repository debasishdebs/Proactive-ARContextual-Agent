package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The image vector of a user pin's REFERENCE Moment photo, for the shadow's image channel (1.4.4 slice B1). Shadow-only: [PlaceShadow] is the only caller and it runs only behind the `placeShadowScore` flag, so
 * with the flag off nothing here is ever invoked.
 *
 * NEVER PERSISTED. The vector is derived from a photo the user can Forget; a stored copy would need its own Forget inventory family (Room table, file, backup rule, wipe step). So it lives in this object's memory
 * only, bounded by [max], and dies with the process. There is no Room, file or prefs write anywhere in this class (a test pins that: it has no storage dependency to write to).
 *
 * Invalidation is REVALIDATION ON EVERY USE (chosen over a Forget / PinMomentLinks listener because it is the simplest safe design): `setMoment` fires no change listener at all, the Forget cascade clears links only
 * in a later sweep (and a crash can skip it), and a retention expiry never notifies anyone. A listener could therefore miss a trigger; asking the two authorities on every use cannot. Each call re-reads the pins' current
 * links ([pinMoments]) and whether each cached Moment still has its photo ([momentFrame], which already honours the tombstone and a missing file), and drops every entry that no longer matches, then the same for the
 * wipe generation and the embedding space. So a Forgotten Moment's vector is never returned, and is dropped from memory at the next attempt. Cost: a few small indexed reads per shadow run, off the attempt's path.
 *
 * NOT IN THE ATTEMPT'S CRITICAL PATH: the shadow already runs beside the model call and after the card. On a miss the embed runs in [scope] (single-flight per key, one embed at a time, only when [mayEmbed], the
 * phone's background power gate, allows) and the shadow waits at most [waitMs] for it; a later attempt finds it cached. A failure is remembered for [FAIL_BACKOFF_MS] so a broken photo is not retried every attempt.
 */
internal class PinReferenceVectors(
    /** pinId -> the pin's CURRENT momentId (only pins that have one), read from the pin store. */
    private val pinMoments: () -> Map<String, String>,
    /** The frame id of the Moment's photo, or null when the Moment is Forgotten (tombstoned second or frame), has no photo left or the file is gone. */
    private val momentFrame: suspend (momentId: String) -> String?,
    /** The photo's JPEG bytes, or null. Production re-checks the tombstone first. Asked only after the zone and fence gates here passed. */
    private val photo: suspend (frameId: String) -> ByteArray?,
    /** The phone's background power gate for image vectors (battery, thermal). False = no new reference embed (the cache is still read). */
    private val mayEmbed: () -> Boolean,
    private val zoneBlocked: suspend () -> Boolean,
    private val generation: () -> Long,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long,
    private val max: Int = MAX_ENTRIES,
    private val waitMs: Long = WAIT_MS,
) {
    private data class Key(val spaceId: String, val pinId: String, val momentId: String)
    private class Entry(val frameId: String, val vector: FloatArray)

    private val lock = Any()
    private val cache = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Key, Entry>?) = size > max }
    private val inflight = HashMap<Key, CompletableDeferred<FloatArray?>>()
    private val failedUntil = HashMap<Key, Long>()
    private val embedMutex = Mutex()
    private var cacheGen = Long.MIN_VALUE
    private var cacheSpace = ""

    /** Entries in memory (tests, and the wipe check). */
    internal fun size(): Int = synchronized(lock) { cache.size }

    /** The reference vectors of [pin] (at most one: the Moment's first photo), or empty: no link, a Forgotten Moment, no photo, not cached yet, power denied, or any failure. [live] is the attempt's fence. */
    suspend fun vectorsFor(pin: PlaceEntity, e: ImageTextEmbedder, live: () -> Boolean = { true }): List<FloatArray> {
        val links = pinMoments()
        revalidate(e, links)
        val momentId = links[pin.id] ?: return emptyList()
        val frameId = momentFrame(momentId) ?: return emptyList()
        val key = Key(e.spaceId, pin.id, momentId)
        synchronized(lock) { cache[key]?.takeIf { it.frameId == frameId }?.let { return listOf(it.vector) } }
        if (!live() || !mayEmbed()) return emptyList()
        val g0 = generation()
        val (job, mine) = synchronized(lock) {
            if ((failedUntil[key] ?: Long.MIN_VALUE) > elapsed()) return emptyList()
            val running = inflight[key]
            if (running != null) running to false else CompletableDeferred<FloatArray?>().also { inflight[key] = it } to true
        }
        if (mine) start(key, frameId, e, g0, job)
        val v = withTimeoutOrNull(waitMs) { job.await() } ?: return emptyList()
        // The wait is over: the Moment may have been Forgotten, the pin edited, a wipe run or the attempt closed meanwhile. Nothing is returned for a link that no longer holds.
        if (!live() || generation() != g0 || pinMoments()[pin.id] != momentId || momentFrame(momentId) != frameId) return emptyList()
        return listOf(v)
    }

    /** Runs the embed in [scope] (it goes on if the shadow stops waiting); [job] is already registered in [inflight], so a second caller joins it and never starts another. */
    private fun start(key: Key, frameId: String, e: ImageTextEmbedder, gen: Long, job: CompletableDeferred<FloatArray?>) {
        scope.launch {
            try {
                job.complete(embed(key, frameId, e, gen))
            } catch (c: CancellationException) {
                job.complete(null); throw c
            } catch (_: Throwable) {
                synchronized(lock) { failedUntil[key] = elapsed() + FAIL_BACKOFF_MS; if (failedUntil.size > max * 4) failedUntil.clear() }
                job.complete(null)
            } finally {
                synchronized(lock) { inflight.remove(key) }
            }
        }
    }

    private suspend fun embed(key: Key, frameId: String, e: ImageTextEmbedder, gen: Long): FloatArray? = embedMutex.withLock {
        // Gates BEFORE the photo is read: the zone, the wipe generation, the power gate, and that the Moment still has this photo.
        if (zoneBlocked() || generation() != gen || !mayEmbed() || momentFrame(key.momentId) != frameId) return null
        val bytes = photo(frameId) ?: return null
        val v = e.embedImage(bytes)
        // And again before it is kept: nothing is stored for a wipe, a zone, or a Moment that went while the model ran.
        if (zoneBlocked() || generation() != gen || pinMoments()[key.pinId] != key.momentId || momentFrame(key.momentId) != frameId) return null
        synchronized(lock) {
            dropIfStale(e, gen)
            if (cacheGen == gen && cacheSpace == key.spaceId) cache[key] = Entry(frameId, v) else return null
        }
        v
    }

    /** The wipe generation or the space moved: empty the cache. */
    private fun dropIfStale(e: ImageTextEmbedder, gen: Long) {
        if (gen != cacheGen || e.spaceId != cacheSpace) { cache.clear(); failedUntil.clear(); cacheGen = gen; cacheSpace = e.spaceId }
    }

    /** Drops every entry whose pin no longer links that Moment, or whose Moment no longer has that photo (Forgotten, tombstoned, missing). */
    private suspend fun revalidate(e: ImageTextEmbedder, links: Map<String, String>) {
        val entries = synchronized(lock) { dropIfStale(e, generation()); cache.entries.map { it.key to it.value.frameId } }
        for ((k, f) in entries) {
            val ok = links[k.pinId] == k.momentId && momentFrame(k.momentId) == f
            if (!ok) synchronized(lock) { cache.remove(k) }
        }
    }

    companion object {
        const val MAX_ENTRIES = 16
        /** How long the shadow waits for a missing reference vector (it runs beside the model call, after the card; the shadow's own budget is 2.5 s). */
        const val WAIT_MS = 800L
        const val FAIL_BACKOFF_MS = 60_000L
    }
}
