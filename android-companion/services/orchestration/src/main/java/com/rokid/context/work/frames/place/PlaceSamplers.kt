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

    override suspend fun tombstoned(frameId: String): Boolean = com.rokid.context.provenance.ProvenanceGate(db()).goneFrames(setOf(frameId), requirePresent = false).isNotEmpty()
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
        app: Context, scope: CoroutineScope, fix: suspend () -> PlaceFix?, zoneBlocked: suspend () -> Boolean, zones: suspend () -> List<com.rokid.context.core.model.zone.PauseZone>?, onCard: (PlaceCard) -> Unit,
        fence: ZoneFence = PlaceGraph.fence,
    ): PlaceSampler {
        val keyframes = KeyframeGraph.get(app)
        return PlaceSampler(
            scope = scope, store = RoomPlaceFrameStore { MemoryGraph.get(app).db }, fix = fix,
            candidates = { lat, lon, up ->
                // Roaming data saver: on a costly network a stored seeded or partial cell is used as it is, and a cell fetch (a text request) happens only when text is allowed there.
                val costly = keyframes.dataGate.costlyNow()
                val fetchOk = up && keyframes.dataGate.decide(com.rokid.context.core.net.DataKind.SMALL).allowed
                withContext(Dispatchers.IO) { PlaceGraph.readyPack(app).candidates(lat, lon, fetchOk, costly) }
            },
            match = { id, lines -> matchCall(app, id, lines, zoneBlocked, currentZoneTicket()) },
            saveMemory = { PlaceMemory.save(app, it, pending = true) }, reveal = { id, gen -> PlaceMemory.reveal(app, id, gen).also { if (it) com.rokid.context.orchestration.proactive.ProactiveTrigger.onPlaceArrival(app) } }, onRollbackFailed = { PlaceRecovery.ensure(app) }, onCard = onCard, clock = System::currentTimeMillis,
            elapsed = android.os.SystemClock::elapsedRealtime, cards = PlaceGraph.port, deleteMemory = { PlaceMemory.delete(app, it) },
            enabled = { PlaceGuideSettings.of(app).enabled }, zoneBlocked = zoneBlocked, networkUp = keyframes::networkUp, backend = { PlaceResolvers.choose(app) },
            admit = keyframes::admitBackgroundCall, photoAllowed = keyframes::dataSaverAllowsPhoto, pipelineOn = { AppConfigStore.isPipelineEnabled(app) },
            generation = { MemoryWipe.generation.get() }, flight = flight, zones = zones, fence = fence, ledger = PlaceMemory.ledger(app),
            log = { PcLog.i(TAG, it) }, // literal text and an outcome name only
            shadow = shadowFor(app, zoneBlocked)::score,
        )
    }

    /** The shadow scorer over the ACTIVE image-text space (today only the on-device Nomic bundle, and only when the user bound it); absent space = `shadow=na`. Reads the flag on every attempt. */
    internal fun shadowFor(app: Context, zoneBlocked: suspend () -> Boolean): PlaceShadow {
        val spaces by lazy { com.rokid.context.work.frames.scene.SpaceImageSpaces(MemoryGraph.get(app).spaces, AiGraph.get(app).embedders) }
        return PlaceShadow(
            enabled = { com.rokid.context.orchestration.forget.DeveloperFlags.placeShadowScore(app) },
            embedder = { spaces.targets().firstOrNull { it.active }?.let { ImageSpaceTargetImageText(it) } },
            photo = { id ->
                if (zoneBlocked() || com.rokid.context.provenance.ProvenanceGate(MemoryGraph.get(app).db).goneFrames(setOf(id), requirePresent = false).isNotEmpty()) null
                else FramePhotos.load(app, listOf(id)).photos.singleOrNull()?.let { android.util.Base64.decode(it.image.base64Data, android.util.Base64.DEFAULT) }
            },
            elapsed = android.os.SystemClock::elapsedRealtime,
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

/**
 * Place Guide's model resolver (slice 1.4.3): the PLACE_CONFIRM role over the VISION_EXTRACT binding. Setting first (Model privacy), then the network ([KeyframeGraph.networkState]). Process-wide, so the one
 * cache and the one decision log serve every sampler. Only the ONLINE slot has a factory: under Offline only, and with no network, the offline slot resolves to a typed "unavailable" until slice 1.4.5.
 */
object PlaceResolvers {
    @Volatile private var resolver: com.rokid.context.ai.resolve.ModelResolver? = null

    fun get(app: Context): com.rokid.context.ai.resolve.ModelResolver = resolver ?: synchronized(this) {
        resolver ?: build(app.applicationContext).also { resolver = it }
    }

    private fun build(app: Context): com.rokid.context.ai.resolve.ModelResolver {
        val ai = AiGraph.get(app)
        return create(com.rokid.context.ai.resolve.ModelPrivacyStore.get(app)::current, { KeyframeGraph.get(app).networkState() }, ai.bindings, ai.endpoints)
    }

    /** The production wiring (the PLACE_CONFIRM role over the VISION_EXTRACT binding), with its inputs injectable so a test can drive it from a real store and real repositories. */
    internal fun create(
        mode: () -> com.rokid.context.core.net.ModelPrivacy, net: () -> com.rokid.context.core.net.NetState,
        bindings: com.rokid.context.ai.RoleBindingRepository, endpoints: com.rokid.context.ai.EndpointRepository, log: (String) -> Unit = { PcLog.i("PcPlace", it) }, // literal resolver lines: role, mode, net, backend and reason names, a fixed label
    ) = com.rokid.context.ai.resolve.ModelResolver(
        mode = mode, net = net,
        factories = mapOf((com.rokid.context.ai.resolve.ResolverRole.PLACE_CONFIRM to com.rokid.context.core.net.Backend.ONLINE) to com.rokid.context.ai.resolve.OnlineRouteFactory(com.rokid.context.ai.Role.VISION_EXTRACT, bindings, endpoints)),
        log = log,
    )

    /** The backend for this attempt. Never throws except for cancellation (the resolver turns a failed load into a typed unavailable). */
    suspend fun choose(app: Context): BackendChoice = choose(get(app))

    internal suspend fun choose(resolver: com.rokid.context.ai.resolve.ModelResolver): BackendChoice = when (val r = resolver.resolve(com.rokid.context.ai.resolve.ResolverRole.PLACE_CONFIRM)) {
        is com.rokid.context.ai.resolve.Resolved.Ready -> BackendChoice(r.backend, com.rokid.context.core.net.ResolveReason.OK, r.mode, r.label)
        is com.rokid.context.ai.resolve.Resolved.Unavailable -> BackendChoice(null, r.reason, r.mode)
    }

    /** The decision alone (nothing is created, nothing is logged), for the Model privacy status line. */
    suspend fun status(app: Context): com.rokid.context.core.net.BackendPick = get(app).peek(com.rokid.context.ai.resolve.ResolverRole.PLACE_CONFIRM)

    fun resetForTests() { synchronized(this) { resolver = null } }
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
