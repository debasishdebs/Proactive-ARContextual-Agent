package com.rokid.context.work.frames.intent

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.IntentOutput
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.CoroutineScope

/** Room behind [IntentFrameStore]. The capture time is `frame_meta.capturedAtMs ?: frame.ts`, never the receive time. */
class RoomIntentFrameStore(private val db: () -> ContextDatabase) : IntentFrameStore {
    override suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame? {
        val m = db().frameMetaDao().newestUnlabelled(sinceReceivedMs) ?: return null
        val cap = m.capturedAtMs ?: db().frameDao().byIds(listOf(m.frameId)).firstOrNull()?.ts?.takeIf { it > 0 }
        return IntentFrame(m.frameId, cap)
    }

    override suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long): Int =
        db().frameMetaDao().setIntent(frameId, label, conf, at)

    override suspend fun tombstoned(frameId: String): Boolean = db().forgetDao().tombstoned(listOf(frameId)).isNotEmpty()
}

/** The production [IntentSampler]: one photo per call through INTENT_CLASSIFY, gated like the extraction pipeline. */
object IntentSamplers {
    /** One per process: a new sampler (source off->on, hub restart) must not start a second upload or reset the minute. */
    private val flight = IntentFlight()

    fun create(
        app: Context,
        scope: CoroutineScope,
        enabled: () -> Boolean,
        episode: () -> Boolean,
        onResult: (frameId: String, capturedAtMs: Long, out: IntentOutput) -> Unit,
        stats: (ok: Boolean?) -> Unit,
    ): IntentSampler {
        val keyframes = KeyframeGraph.get(app)
        return IntentSampler(
            scope = scope, store = RoomIntentFrameStore { MemoryGraph.get(app).db }, classify = { id -> classify(app, id) },
            clock = System::currentTimeMillis, episode = episode, onResult = onResult, enabled = enabled, stats = stats,
            admit = keyframes::admitBackgroundCall, pipelineOn = { AppConfigStore.isPipelineEnabled(app) },
            generation = { MemoryWipe.generation.get() }, flight = flight,
            preflight = { id -> FramePhotos.load(app, listOf(id)).photos.isNotEmpty() },
        )
    }

    /** One photo, one INTENT_CLASSIFY call (CallClass.EXTRACT, so the AI call pace applies). Null = the photo is gone or unreadable. */
    internal suspend fun classify(app: Context, frameId: String): IntentOutput? {
        val photo = FramePhotos.load(app, listOf(frameId)).photos.singleOrNull() ?: return null
        val r = AiGraph.get(app).runner.run(RoleCall(PromptKey.INTENT_CLASSIFY, PromptVars(), images = listOf(photo.image), callClass = CallClass.EXTRACT))
        return r.output as? IntentOutput
    }
}
