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

/** The production wiring of [PlaceSampler]: the pack, the PLACE_MATCH call and the place memory. */
object PlaceSamplers {
    private val flight = PlaceFlight()

    /** [fix] is the phone's fresh foreground fix; [zoneBlocked] is true while a pause zone is active, approaching or unreadable. */
    fun create(
        app: Context, scope: CoroutineScope, fix: suspend () -> PlaceFix?, zoneBlocked: suspend () -> Boolean, onCard: (PlaceCard) -> Unit,
    ): PlaceSampler {
        val keyframes = KeyframeGraph.get(app)
        return PlaceSampler(
            scope = scope, store = RoomPlaceFrameStore { MemoryGraph.get(app).db }, fix = fix,
            candidates = { lat, lon, up -> withContext(Dispatchers.IO) { PlaceGraph.pack(app).candidates(lat, lon, up) } },
            match = { id, lines -> matchCall(app, id, lines) },
            saveMemory = { PlaceMemory.save(app, it) }, onCard = onCard, clock = System::currentTimeMillis,
            enabled = { PlaceGuideSettings.of(app).enabled }, zoneBlocked = zoneBlocked, networkUp = keyframes::networkUp,
            admit = keyframes::admitBackgroundCall, pipelineOn = { AppConfigStore.isPipelineEnabled(app) },
            generation = { MemoryWipe.generation.get() }, flight = flight,
            log = { PcLog.i(TAG, it) }, // literal text and an outcome name only
        )
    }

    private const val TAG = "PcPlace"

    /**
     * One photo, one PLACE_MATCH call through the same router and choke point as VISION_EXTRACT / INTENT_CLASSIFY (CallClass.EXTRACT, so the AI call pace applies; the photo carries its ref and
     * the liveness the router re-checks inside the permit). The prompt gets the numbered names and kinds inside a data block, no summaries. Null = the photo is gone or the call failed.
     */
    internal suspend fun matchCall(app: Context, frameId: String, lines: List<String>): PlaceMatchResult? {
        val photo = FramePhotos.load(app, listOf(frameId)).photos.singleOrNull() ?: return null
        val r = try {
            AiGraph.get(app).runner.run(RoleCall(PromptKey.PLACE_MATCH, PromptVars(recentContext = PromptComposer.dataBlock("candidates", lines)), images = listOf(photo.image.copy(ref = frameId)), callClass = CallClass.EXTRACT,
                liveness = com.rokid.context.work.frames.FrameLiveness.forApp(app, MemoryWipe.generation.get()), sources = com.rokid.context.model.PayloadSources.frames(listOf(frameId))))
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

    /** Writes the memory and its `frame_source` row in one transaction (so Forget of the photo cascades). False = nothing written (Delete all, or the photo was forgotten, since the match). */
    suspend fun save(app: Context, r: PlaceMemoryRequest, now: Long = System.currentTimeMillis()): Boolean {
        val db = MemoryGraph.get(app).db
        val id = idFor(r.frameId)
        return db.withTransaction {
            val ok = PipelineRecords.appendFact(
                app, id, r.frameId, text(r.place.name, r.parentName, r.confidence), TYPE, tags(r.place, r.parentName), r.confidence.toDouble(), MemoryTier.LONG, ReviewState.OK,
                createdAt = now, generation = r.generation, imageDelivered = r.delivered,
            )
            if (ok) {
                db.frameSourceDao().deleteForMemory(id)
                db.frameSourceDao().insertAll(listOf(FrameSourceEntity(id, r.frameId, null, "FACT")))
            }
            ok
        }
    }
}
