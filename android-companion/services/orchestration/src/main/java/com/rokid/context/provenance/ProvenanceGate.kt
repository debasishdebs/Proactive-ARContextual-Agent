package com.rokid.context.provenance

import androidx.room.withTransaction
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.ForgetFence
import com.rokid.context.memory.forget.PHOTO_CITE_PREFIX
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.place.ZoneTicket
import com.rokid.context.model.GonePhotos
import com.rokid.context.model.PayloadSources
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.CancellationException

/**
 * THE choke point of text built FROM photos (captions, scene and OCR text, descriptions, place-match output, derived facts, summaries, asks and nudges that quote them), the text twin of the image gate
 * (`ModelRouter`'s liveness re-check). Spec 2026-11-16 section A. Text derived from a photo is SENT to a model, KEPT, SHOWN or EXPORTED only if every source it was built from is still live:
 *
 *  - the wipe generation read before the work is unchanged (Delete all has not run);
 *  - no source photo is tombstoned (a Forget), and for a send its frame row still exists;
 *  - no source memory or `frame:` cite is forgotten (gone or INVALIDATED); an EXPIRED photo whose metadata stays is still live (no JPEG is needed);
 *  - no source mark label is gone;
 *  - the read [Leases.Lease] (when the flow holds one) was not revoked by a Forget;
 *  - and, for text that is fresh from a photo ([Zone.ENFORCE]: the Place Guide), the zone fence is not held or blocked.
 * Every doubt is a refusal: a database error or an unreadable source is [Reason.UNCHECKABLE], never "live" (fail closed). Ids only; nothing here ever sees or logs text.
 *
 * ONE implementation of those rules. The router's [PhotoLiveness] ([photoLiveness]), the write-side fences ([commit], [check] inside a writer's own transaction) and the read side ([liveIds], [liveFilter]) all call it.
 * The export keeps the same rule in SQL: `ProvenanceSql.LIVE_DERIVED`. `ProvenanceChokePointTest` fails when main code re-implements the raw checks outside the gate, the Forget engine and the DAOs,
 * or when a new writer of derived text appears that does not ask the gate.
 */
class ProvenanceGate(
    private val db: ContextDatabase,
    /** The Place Guide zone state (production: `PlaceGraph.fence::blocked`). */
    private val zoneBlocked: () -> Boolean = { false },
    private val currentGeneration: () -> Long = { MemoryWipe.generation.get() },
) {
    /** [ENFORCE]: the text is fresh from a photo (place match) and a held zone fence refuses it. [IGNORE]: stored text read back (an Ask over memories), which a zone does not hide. */
    enum class Zone { IGNORE, ENFORCE }

    enum class Reason { WIPED, LEASE_REVOKED, ZONE_BLOCKED, PHOTO_GONE, MEMORY_GONE, MARK_GONE, UNCHECKABLE }

    sealed interface Verdict {
        data object Live : Verdict
        data class Denied(val reason: Reason, val ids: Set<String> = emptySet()) : Verdict
    }

    sealed interface Commit<out T> {
        data class Committed<T>(val value: T) : Commit<T>
        data class Refused(val denied: Verdict.Denied) : Commit<Nothing>
    }

    /**
     * Reads only, so it is callable inside the caller's own Room transaction. [requirePresent]: a source photo must also still have its `frame` row (the send rule); false = only "not tombstoned" (the write rule:
     * a photo whose JPEG row expired is still live). The first failing rule wins, in the order of [Reason].
     */
    suspend fun check(sources: PayloadSources, generation: Long, zone: Zone = Zone.IGNORE, lease: Leases.Lease? = null, requirePresent: Boolean = false): Verdict = try {
        when {
            currentGeneration() != generation -> Verdict.Denied(Reason.WIPED)
            lease != null && lease.isRevoked -> Verdict.Denied(Reason.LEASE_REVOKED)
            zone == Zone.ENFORCE && zoneBlocked() -> Verdict.Denied(Reason.ZONE_BLOCKED)
            else -> {
                val photos = goneFrames(sources.frameIds, requirePresent)
                if (photos.isNotEmpty()) Verdict.Denied(Reason.PHOTO_GONE, photos)
                else {
                    val memories = goneMemories(sources.memoryIds)
                    if (memories.isNotEmpty()) Verdict.Denied(Reason.MEMORY_GONE, memories)
                    else {
                        val marks = goneMarks(sources.markIds)
                        if (marks.isNotEmpty()) Verdict.Denied(Reason.MARK_GONE, marks)
                        // the zone is asked AGAIN after the reads (they suspend): live only if it still holds at the end
                        else if (zone == Zone.ENFORCE && zoneBlocked()) Verdict.Denied(Reason.ZONE_BLOCKED)
                        else Verdict.Live
                    }
                }
            }
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        Verdict.Denied(Reason.UNCHECKABLE)
    }

    /** [check] for a plain id set as flows carry them: a `frame:<id>` cite or a memory id. */
    suspend fun check(ids: Collection<String>, generation: Long, zone: Zone = Zone.IGNORE): Verdict = check(PayloadSources.stored(emptyList(), ids), generation, zone)

    /**
     * The write side: ONE Room transaction that runs [check] and, only when it holds, [block]. Opens no read lease of its own: a flow that holds one passes it (and a revoked lease writes nothing). The block's own
     * writes and the dependency rows (memory_source, frame_source) belong in it, so a later Forget always finds them. A refusal writes nothing.
     */
    suspend fun <T> commit(sources: PayloadSources, generation: Long, zone: Zone = Zone.IGNORE, lease: Leases.Lease? = null, block: suspend () -> T): Commit<T> = db.withTransaction {
        when (val v = check(sources, generation, zone, lease)) {
            is Verdict.Denied -> Commit.Refused(v)
            Verdict.Live -> Commit.Committed(block())
        }
    }

    /** The read side: those of [ids] (memory ids and `frame:` cites) still live. A surface that shows or sends text keeps only these. */
    suspend fun liveIds(ids: Collection<String>): Set<String> = try { ForgetFence.liveIds(db, ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { emptySet() }

    /** True when every one of [ids] is live (false on any doubt). */
    suspend fun allLive(ids: Collection<String>): Boolean = try { ForgetFence.allLive(db, ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { false }

    /** Keeps the [items] whose every id ([idsOf]) is live. */
    suspend fun <T> liveFilter(items: List<T>, idsOf: (T) -> Collection<String>): List<T> =
        if (allLive(items.flatMap(idsOf))) items else items.filter { allLive(idsOf(it)) }

    /** A photo is live while its `frame` row exists (when [requirePresent]) and it is not tombstoned. Returns the gone ones. */
    suspend fun goneFrames(ids: Set<String>, requirePresent: Boolean = true): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val list = ids.toList()
        val tombstoned = list.chunked(MAX_IN).flatMap { db.forgetDao().tombstoned(it) }.toSet()
        if (!requirePresent) return tombstoned
        val present = list.chunked(MAX_IN).flatMap { db.frameDao().byIds(it) }.map { it.id }.toSet()
        return ids - (present - tombstoned)
    }

    /**
     * Retention r1 (finding 5): the photos among [ids] that a user FORGOT. Expiry also tombstones a photo (the extraction lock) but keeps its `frame_meta`, so a tombstoned photo that still has metadata merely expired and
     * is NOT returned: a record that cites an expired photo (a suggestion) stays live. A tombstoned photo with no metadata (forgotten, or a legacy frame) is returned.
     */
    suspend fun forgottenFrames(ids: Set<String>): Set<String> {
        val tombstoned = goneFrames(ids, requirePresent = false)
        if (tombstoned.isEmpty()) return emptySet()
        val expired = tombstoned.toList().chunked(MAX_IN).flatMap { db.forgetDao().keyframeIds(it) }.toSet()
        return tombstoned - expired
    }

    suspend fun goneMemories(ids: Set<String>): Set<String> = if (ids.isEmpty()) emptySet() else ForgetFence.goneAmong(db, ids)

    suspend fun goneMarks(ids: Set<String>): Set<String> = ids.filter { db.canvasSlice3Dao().label(it) == null }.toSet()

    /**
     * The router's [PhotoLiveness] over this gate: the image choke point and the text choke point are one set of rules. [zone] (Place Guide) also refuses while the fence is blocked or [ticket]'s attempt closed,
     * asked before and after the reads. The slot is the [DataOpGate]'s dispatch side, so a Forget either finished before the re-check or waits for the upload.
     */
    fun photoLiveness(generation: Long, zone: Zone = Zone.IGNORE, ticket: ZoneTicket? = null, gate: () -> DataOpGate = { DataOpGate.shared }): PhotoLiveness = object : PhotoLiveness {
        override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = gate().dispatch(block)
        private fun closed() = (zone == Zone.ENFORCE && zoneBlocked()) || ticket?.isOpen() == false
        override suspend fun goneMemories(ids: Set<String>): Set<String> = if (closed() || currentGeneration() != generation) ids else try { this@ProvenanceGate.goneMemories(ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { ids }
        override suspend fun goneMarks(ids: Set<String>): Set<String> = if (closed() || currentGeneration() != generation) ids else try { this@ProvenanceGate.goneMarks(ids) } catch (c: CancellationException) { throw c } catch (_: Exception) { ids }
        override suspend fun gone(ids: Set<String>): GonePhotos {
            if (currentGeneration() != generation) return GonePhotos(ids, wiped = true)
            if (closed()) return GonePhotos(ids)
            val gone = try { goneFrames(ids, requirePresent = true) } catch (c: CancellationException) { throw c } catch (_: Exception) { ids }
            return if (closed()) GonePhotos(ids) else GonePhotos(gone)
        }
    }

    /** True when a Delete all ran since [generation] was read (the wipe rule of [check], for flows that have no sources to name). */
    fun wiped(generation: Long): Boolean = currentGeneration() != generation

    companion object {
        private const val MAX_IN = 500

        /** The wipe rule for code that holds no database: a Delete all ran since [generation] was read. Same rule as [wiped] over the app's wipe counter. */
        fun isWiped(generation: Long): Boolean = MemoryWipe.generation.get() != generation

        /** The gate over the app's memory database, with the Place Guide zone fence supplied by the caller (the orchestration layer's `PlaceGraph.fence::blocked`). */
        fun forGraph(g: MemoryGraph, zoneBlocked: () -> Boolean = { false }) = ProvenanceGate(g.db, zoneBlocked)

        fun forApp(app: android.content.Context, zoneBlocked: () -> Boolean = { false }) = forGraph(MemoryGraph.get(app), zoneBlocked)

        /** Photo cite ids as flows carry them. */
        fun cite(frameId: String) = PHOTO_CITE_PREFIX + frameId
    }
}
