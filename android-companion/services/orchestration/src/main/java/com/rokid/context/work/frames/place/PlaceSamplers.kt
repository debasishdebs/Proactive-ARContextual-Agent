package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.PlaceMatchOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.core.log.PcLog
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.prompt.PromptComposer
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.place.ZoneTicket
import com.rokid.context.memory.place.currentZoneTicket
import java.util.Locale

/** Room behind [PlaceFrameStore]. The capture time is `frame_meta.capturedAtMs ?: frame.ts`, never the receive time. */
class RoomPlaceFrameStore(private val db: () -> com.rokid.context.memory.db.ContextDatabase) : PlaceFrameStore {
    override suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame? {
        val m = db().frameMetaDao().newestDwell(sinceReceivedMs) ?: return null
        val cap = m.capturedAtMs ?: db().frameDao().byIds(listOf(m.frameId)).firstOrNull()?.ts?.takeIf { it > 0 }
        return PlaceFrame(m.frameId, cap)
    }

    override suspend fun tombstoned(frameId: String): Boolean = db().forgetDao().tombstoned(listOf(frameId)).isNotEmpty()
}

/**
 * The photo liveness the router asks inside its upload permit (after the permit wait, in the upload slot, before every attempt including retries): while a pause zone is active,
 * approaching or unreadable, or the attempt's fence closed, the photo counts as gone, so the call is dropped with nothing sent. The inner check suspends (Room reads), so the fence is asked
 * AGAIN after it: the answer is "live" only if it still holds when the reads are done.
 */
internal class ZoneFencedLiveness(
    private val inner: com.rokid.context.model.PhotoLiveness, private val zoneBlocked: suspend () -> Boolean, private val ticket: ZoneTicket? = null,
) : com.rokid.context.model.PhotoLiveness by inner {
    override suspend fun gone(ids: Set<String>): com.rokid.context.model.GonePhotos {
        val all = com.rokid.context.model.GonePhotos(ids)
        if (ticket?.isOpen() == false || zoneBlocked()) return all
        val g = inner.gone(ids)
        return if (ticket?.isOpen() == false || zoneBlocked()) all else g
    }
}

/** The production wiring of [PlaceSampler]: the pack, the PLACE_MATCH call and the place memory. */
object PlaceSamplers {
    internal val flight = PlaceFlight()

    /** [fix] is the phone's fresh foreground fix; [zoneBlocked] is true while a pause zone is active, approaching or unreadable. */
    fun create(
        app: Context, scope: CoroutineScope, fix: suspend () -> PlaceFix?, zoneBlocked: suspend () -> Boolean, onCard: (PlaceCard) -> Unit,
        fence: ZoneFence = PlaceGraph.fence,
    ): PlaceSampler {
        val keyframes = KeyframeGraph.get(app)
        return PlaceSampler(
            scope = scope, store = RoomPlaceFrameStore { MemoryGraph.get(app).db }, fix = fix,
            candidates = { lat, lon, up -> withContext(Dispatchers.IO) { PlaceGraph.pack(app).candidates(lat, lon, up) } },
            match = { id, lines -> matchCall(app, id, lines, zoneBlocked, currentZoneTicket()) },
            saveMemory = { PlaceMemory.save(app, it, pending = true) }, reveal = { id, gen -> PlaceMemory.reveal(app, id, gen) }, onRollbackFailed = { PlaceRecovery.ensure(app) }, onCard = onCard, clock = System::currentTimeMillis,
            elapsed = android.os.SystemClock::elapsedRealtime, cards = PlaceGraph.port, deleteMemory = { PlaceMemory.delete(app, it) },
            enabled = { PlaceGuideSettings.of(app).enabled }, zoneBlocked = zoneBlocked, networkUp = keyframes::networkUp,
            admit = keyframes::admitBackgroundCall, pipelineOn = { AppConfigStore.isPipelineEnabled(app) },
            generation = { MemoryWipe.generation.get() }, flight = flight, fence = fence, ledger = PlaceMemory.ledger(app),
            log = { PcLog.i(TAG, it) }, // literal text and an outcome name only
        )
    }

    private const val TAG = "PcPlace"

    /**
     * One photo, one PLACE_MATCH call through the same router and choke point as VISION_EXTRACT / INTENT_CLASSIFY (CallClass.EXTRACT, so the AI call pace applies; the photo carries its ref and
     * the liveness the router re-checks inside the permit). The prompt gets the numbered names and kinds inside a data block, no summaries. Null = the photo is gone or the call failed.
     */
    internal suspend fun matchCall(app: Context, frameId: String, lines: List<String>, zoneBlocked: suspend () -> Boolean = { false }, ticket: ZoneTicket? = null): PlaceMatchResult? {
        val photo = FramePhotos.load(app, listOf(frameId)).photos.singleOrNull() ?: return null
        val r = try {
            AiGraph.get(app).runner.run(RoleCall(PromptKey.PLACE_MATCH, PromptVars(recentContext = PromptComposer.dataBlock("candidates", lines)), images = listOf(photo.image.copy(ref = frameId)), callClass = CallClass.EXTRACT,
                liveness = ZoneFencedLiveness(com.rokid.context.work.frames.FrameLiveness.forApp(app, MemoryWipe.generation.get()), zoneBlocked, ticket), sources = com.rokid.context.model.PayloadSources.frames(listOf(frameId))))
        } catch (f: com.rokid.context.ai.PhotoForgottenException) {
            return null
        }
        val out = r.output as? PlaceMatchOutput ?: return null
        return PlaceMatchResult(out, r.imagesDelivered)
    }
}

/** The one `type="place"` memory per matched photo, through the existing memory writer ([PipelineRecords.appendFact]): no schema change. */
object PlaceMemory {
    const val TYPE = "place"
    fun idFor(frameId: String) = "rokid_place_$frameId"

    fun text(name: String, parent: String?, confidence: Float): String =
        "Looked at $name" + (parent?.let { " ($it)" } ?: "") + " · photo match " + String.format(Locale.ROOT, "%.2f", confidence)

    fun tags(p: com.rokid.context.memory.place.PlaceEntity, parent: String?): List<String> = listOfNotNull("place", p.id.takeIf { it.startsWith("wd:") }, p.kind, parent)

    /** Takes back the memory [save] wrote for [frameId] (and its `frame_source` row), in one transaction. Idempotent. */
    suspend fun delete(app: Context, frameId: String) {
        val g = MemoryGraph.get(app)
        val id = idFor(frameId)
        g.db.withTransaction { g.db.frameSourceDao().deleteForMemory(id); g.memories.deleteByIds(listOf(id)) }
    }

    /** Writes the memory and its `frame_source` row in one transaction (so Forget of the photo cascades). False = nothing written (Delete all, or the photo was forgotten, since the match). */
    suspend fun save(app: Context, r: PlaceMemoryRequest, now: Long = System.currentTimeMillis(), pending: Boolean = false): Boolean {
        val db = MemoryGraph.get(app).db
        val id = idFor(r.frameId)
        return try { db.withTransaction {
            val ok = PipelineRecords.appendFact(
                app, id, r.frameId, text(r.place.name, r.parentName, r.confidence), TYPE, tags(r.place, r.parentName), r.confidence.toDouble(), MemoryTier.LONG, if (pending) ReviewState.INVALIDATED else ReviewState.OK,
                createdAt = now, generation = r.generation, imageDelivered = r.delivered,
            )
            if (ok) {
                db.frameSourceDao().deleteForMemory(id)
                db.frameSourceDao().insertAll(listOf(FrameSourceEntity(id, r.frameId, null, "FACT")))
                // Zone authority is validated INSIDE the transaction, after the writes and before the commit: refused = the whole transaction rolls back, nothing becomes durable.
                if (!r.allowed()) throw NotAllowed()
            }
            ok
        } } catch (_: NotAllowed) { false }
    }

    /**
     * Makes a memory written with `pending = true` readable (INVALIDATED -> OK), only once its card is out. INVALIDATED is the state every read path already excludes (retrieval, FTS search, Activity/Home
     * lists, Ask, export, the embedding index and its vectors), so a committed memory whose rollback failed is never readable. Refused (false) after Delete all ([generation] moved) or when the photo was forgotten.
     */
    suspend fun reveal(app: Context, frameId: String, generation: Long): Boolean {
        val g = MemoryGraph.get(app)
        return g.db.withTransaction {
            if (com.rokid.context.provenance.ProvenanceGate.forGraph(g).check(com.rokid.context.model.PayloadSources.frames(listOf(frameId)), generation) != com.rokid.context.provenance.ProvenanceGate.Verdict.Live) return@withTransaction false
            g.db.memoryViewDao().moveReviewState(idFor(frameId), ReviewState.INVALIDATED.name, ReviewState.OK.name) == 1
        }
    }

    private class NotAllowed : RuntimeException()

    /** The durable [PlaceMemoryLedger]: a key in the existing `place_guide` prefs file (already owned by a Forget family, KEPT by Delete all, excluded from backup and device transfer), committed synchronously so a mark survives the process. Only frame ids. */
    fun ledger(app: Context): PlaceMemoryLedger = PrefsLedger(app.applicationContext.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE))

    internal class PrefsLedger(private val prefs: android.content.SharedPreferences) : PlaceMemoryLedger {
        private val lock = Any()
        override fun mark(frameId: String) { synchronized(lock) { prefs.edit().putStringSet(KEY, (pending() + frameId)).commit() } }
        override fun clear(frameId: String) { synchronized(lock) { val p = pending(); if (frameId in p) prefs.edit().putStringSet(KEY, p - frameId).commit() } }
        override fun pending(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        private companion object { const val KEY = "pending_memory_takebacks" }
    }
}
