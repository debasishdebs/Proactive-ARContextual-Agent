package com.rokid.context.orchestration.nudge

import android.content.Context
import android.os.PowerManager
import com.rokid.context.ai.canvas.WindowAggregate
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.SceneInputExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.RagCore
import kotlinx.serialization.json.buildJsonObject

/** `trigger.signal` over the stored Scenes: the sensor feature of the newest [count] Scenes of the last day, oldest first (a Scene without that number gives null). */
class SceneSignalValues(db: ContextDatabase) : SignalValues {
    private val scenes = SceneInputExecutor(db)

    override suspend fun values(feature: String, count: Int, nowMs: Long): List<Double?> =
        scenes.run(nowMs - DAY_MS, nowMs).takeLast(count).map { v -> WindowAggregate.featureOf(buildJsonObject { v.sensor?.let { put("sensor", it.toJson()) } }, feature) }

    private companion object { const val DAY_MS = 86_400_000L }
}

/** The image embedding of the newest stored photo of the last [windowMs] (by Moment), the query of an IMAGE retrieval; null when there is none or it has no embedding yet. */
class NewestPhotoVector(
    private val db: ContextDatabase, private val windowMs: Long = 10 * 60_000L,
    /** The id of the ACTIVE image space (the one recall searches); null = unknown. A photo embedded in another space is never used as a query. */
    private val activeSpace: suspend () -> String? = { null },
    /** Called with a fixed reason token when a photo is skipped (recorded as a trace row by the app). */
    private val onSkip: suspend (String) -> Unit = {},
) : TriggerImages {
    override suspend fun photo(nowMs: Long): TriggerPhoto? {
        val active = activeSpace()
        val md = db.momentDao()
        val moments = md.momentsBetween(MomentId.ofMs(maxOf(0L, nowMs - windowMs)), MomentId.ofMs(nowMs)).sortedByDescending { it.momentId }.take(MAX_MOMENTS)
        for (m in moments) {
            val frames = md.partsOf(listOf(m.momentId)).filter { it.kind == MomentPartKind.FRAME }.sortedByDescending { it.partTimeMs }
            for (part in frames) {
                val meta = db.frameMetaDao().byIds(listOf(part.refId)).firstOrNull() ?: continue
                val space = meta.imageVectorSpace ?: continue
                if (space != active) { onSkip("image_space_mismatch"); return null } // incompatible embeddings of equal size would match wrongly: skip, never guess
                val blob = db.frameVectorDao().get(meta.frameId, space)?.blob ?: continue
                runCatching { VectorCodec.decode(blob) }.getOrNull()?.let { return TriggerPhoto(meta.frameId, it) }
            }
        }
        return null
    }

    private companion object { const val MAX_MOMENTS = 5 }
}

/** What the app gives the engine for the slice C triggers. */
object NudgeSeamsFactory {
    fun seams(app: Context): TriggerSeams {
        val mem = MemoryGraph.get(app)
        return TriggerSeams(
            meta = DaoTriggerMeta(mem.db.canvasSlice3Dao()), signals = SceneSignalValues(mem.db), images = NewestPhotoVector(
                mem.db,
                activeSpace = { com.rokid.context.work.frames.scene.SpaceImageSpaces(mem.spaces, com.rokid.context.ai.AiGraph.get(app).embedders).targets().firstOrNull { it.active }?.spaceId },
                onSkip = { r -> val t = System.currentTimeMillis(); mem.traces.upsert(com.rokid.context.memory.db.SummaryTraceEntity("nudge_image_$t", "NUDGE_IMAGE_SKIPPED", t, r)) },
            ),
            paused = { mem.db.pauseDao().countCovering(System.currentTimeMillis()) > 0 || (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true },
        )
    }

    /** The core a Nudge retrieves through: the photo store is searched by a trigger's image vector, behind the same photo gate as an Ask; no image is decrypted or attached to a Nudge (no endpoint is given). */
    fun ragCore(app: Context): RagCore = RagCore(
        MemoryGraph.get(app), photosByVector = RagCore.photosByVectorFrom(app), photosAllowed = { AnswerService.photosAllowedNow(app) },
    )
}
