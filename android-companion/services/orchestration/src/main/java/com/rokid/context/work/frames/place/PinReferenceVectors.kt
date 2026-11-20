package com.rokid.context.work.frames.place

import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlaceEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The image vector of a user pin's REFERENCE Moment photo, for the shadow's image channel (1.4.4 slice B1). Shadow-only: [PlaceShadow] is the only caller and it runs only behind the `placeShadowScore` flag; production creates this object LAZILY
 * (on the first shadow run), so with the flag off it is never allocated.
 *
 * NEVER PERSISTED. The vector is derived from a photo the user can Forget; a stored copy would need its own Forget inventory family (Room table, file, backup rule, wipe step). So it lives in this object's memory
 * only, bounded by [max], and dies with the process. There is no Room, file or prefs write anywhere in this class (a test pins that: it has no storage dependency to write to).
 *
 * Invalidation is TWO layers. (1) EAGER: the object is registered in [ShadowCaches], which Delete all, every Forget listener (single, range, user Forget, retention expiry) and every pin link change call, so the vectors
 * leave memory at once (and when the flag goes OFF). (2) REVALIDATION ON EVERY USE, because a listener can miss a trigger (a crash skips the Forget cascade, `setMoment` is not a Forget): each score re-reads the pins'
 * current links ([pinMoments]) ONCE and whether each cached Moment still has its photo ([momentFrame]: the tombstone and the row; a retention run that deletes the file but keeps the row notifies the listeners, which
 * is layer 1), drops every entry that no longer matches, then the same for the wipe generation and the embedding space. The shadow asks [stillValid] once more right before it uses the vectors, so a Forget that
 * lands DURING the embed, the wait or the scoring discards that score. Cost: a few small indexed reads per shadow run, off the attempt's path.
 *
 * NOT IN THE ATTEMPT'S CRITICAL PATH: the shadow already runs beside the model call and after the card. On a miss the embed runs in [scope] (single-flight per key, one embed at a time, only when [mayEmbed], the
 * phone's background power gate, allows) and the shadow waits at most [waitMs] for it; a later attempt finds it cached. A failure is remembered for [FAIL_BACKOFF_MS] so a broken photo is not retried every attempt.
 */
internal class PinReferenceVectors(
    /** pinId -> the pin's CURRENT momentId (only pins that have one), read from the pin store. */
    private val pinMoments: () -> Map<String, String>,
    /** The frame id of the Moment's photo, or null when the Moment is Forgotten (tombstoned second or frame), has no photo left or has no photo row left. */
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
    /** Bumped by [clear]: an embed that started before it never stores. */
    private var clearEpoch = 0L

    /** Entries in memory (tests, and the wipe check). */
    internal fun size(): Int = synchronized(lock) { cache.size }

    /** Drops everything now (the eager clear: Forget, Delete all, a pin link change, the flag going off). An embed already running stores nothing. */
    fun clear() { synchronized(lock) { cache.clear(); failedUntil.clear(); clearEpoch++ } }

    /** The reference vectors of [pin] (at most one: the Moment's first photo), or empty. */
    suspend fun vectorsFor(pin: PlaceEntity, e: ImageTextEmbedder, live: () -> Boolean = { true }): List<FloatArray> = vectorsForAll(listOf(pin), e, live)[pin.id].orEmpty()

    /**
     * The reference vectors of every pin in [pins], asked together: ONE revalidation and ONE read of the links per score, and the cold misses wait in parallel (one wait, not one per pin). A pin with no entry has
     * no link, a Forgotten Moment, no photo, no vector yet, power denied or a failure. [live] is the attempt's fence (ticket, pipeline, wipe generation).
     */
    suspend fun vectorsForAll(pins: List<PlaceEntity>, e: ImageTextEmbedder, live: () -> Boolean = { true }): Map<String, List<FloatArray>> {
        val links = pinMoments()
        revalidate(e, links)
        return kotlinx.coroutines.coroutineScope {
            pins.map { p -> async { p.id to (try { one(p, e, links, live) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptyList() }) } }.awaitAll().toMap()
        }
    }

    private suspend fun one(pin: PlaceEntity, e: ImageTextEmbedder, links: Map<String, String>, live: () -> Boolean): List<FloatArray> {
        val momentId = links[pin.id] ?: return emptyList()
        val frameId = momentFrame(momentId) ?: return emptyList()
        val key = Key(e.spaceId, pin.id, momentId)
        val hit = synchronized(lock) { cache[key]?.takeIf { it.frameId == frameId }?.vector }
        // A Forget can land between the check above and here: the answer is asked again right before a cached vector leaves this class.
        if (hit != null) return if (momentFrame(momentId) == frameId && live()) listOf(hit) else emptyList()
        if (!live() || !mayEmbed()) return emptyList()
        val g0 = generation()
        val (job, mine) = synchronized(lock) {
            if ((failedUntil[key] ?: Long.MIN_VALUE) > elapsed()) return emptyList()
            val running = inflight[key]
            if (running != null) running to false else CompletableDeferred<FloatArray?>().also { inflight[key] = it } to true
        }
        if (mine) start(key, frameId, e, g0, live, job)
        val v = withTimeoutOrNull(waitMs) { job.await() } ?: return emptyList()
        // The wait is over: the Moment may have been Forgotten, the pin edited, a wipe run or the attempt closed meanwhile. Nothing is returned for a link that no longer holds.
        if (!live() || generation() != g0 || pinMoments()[pin.id] != momentId || momentFrame(momentId) != frameId) return emptyList()
        return listOf(v)
    }

    /**
     * True when every vector in [used] (pinId -> what [vectorsForAll] returned) is still the cached vector of a pin that still links the same Moment whose photo is still there. The shadow asks this once after
     * scoring: a single-Moment Forget (or a link change, or a wipe) that landed while it ran makes it false and the score is dropped, so no score uses a just-forgotten photo.
     */
    suspend fun stillValid(used: Map<String, List<FloatArray>>, e: ImageTextEmbedder): Boolean {
        val withRef = used.filterValues { it.isNotEmpty() }
        if (withRef.isEmpty()) return true
        val links = pinMoments()
        val held = synchronized(lock) { if (cacheGen != generation() || cacheSpace != e.spaceId) emptyList() else cache.entries.map { Triple(it.key, it.value.frameId, it.value.vector) } }
        for ((pinId, vs) in withRef) {
            val h = held.firstOrNull { it.first.pinId == pinId && it.third === vs[0] } ?: return false
            if (links[pinId] != h.first.momentId || momentFrame(h.first.momentId) != h.second) return false
        }
        return true
    }

    /** Runs the embed in [scope] (it goes on if the shadow stops waiting); [job] is already registered in [inflight], so a second caller joins it and never starts another. */
    private fun start(key: Key, frameId: String, e: ImageTextEmbedder, gen: Long, live: () -> Boolean, job: CompletableDeferred<FloatArray?>) {
        scope.launch {
            try {
                job.complete(embed(key, frameId, e, gen, live))
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

    private suspend fun embed(key: Key, frameId: String, e: ImageTextEmbedder, gen: Long, live: () -> Boolean): FloatArray? = embedMutex.withLock {
        // Gates BEFORE the photo is read: the attempt's own gate (ticket, switch, pipeline, wipe), the zone, the wipe generation, the power gate, and that the Moment still has this photo.
        val epoch = synchronized(lock) { clearEpoch }
        if (!live() || zoneBlocked() || generation() != gen || !mayEmbed() || momentFrame(key.momentId) != frameId) return null
        val bytes = photo(frameId) ?: return null
        val v = e.embedImage(bytes)
        // And again before it is kept: nothing is stored for a wipe, a zone, or a Moment that went while the model ran.
        if (!live() || zoneBlocked() || generation() != gen || pinMoments()[key.pinId] != key.momentId || momentFrame(key.momentId) != frameId) return null
        synchronized(lock) {
            // Inside the lock: a wipe or a clear that landed since the check above stores nothing and does not clear newer entries.
            if (generation() != gen || clearEpoch != epoch) return null
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
