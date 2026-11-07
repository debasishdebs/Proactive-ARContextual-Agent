package com.rokid.context.work.frames

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.model.DispatchGuard
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import java.util.Collections

/**
 * Codex r9 P1-1: installed (as a coroutine-context element) around one extraction over [frameIds]. Every provider call that carries photos runs, inside its
 * call permit, under the [DataOpGate]'s dispatch side, and first re-checks that every frame of the batch is still live (row present, not tombstoned or
 * forgotten: [PipelineRecords.framesByIds]) and that Delete all has not run since [generation]. If not, the call is dropped unsent ([Forgotten]) and
 * [dropped] names the frames that went. The batch prompt also carries each frame's metadata, so one forgotten frame drops the whole call.
 */
class FrameDispatchGuard(
    private val frameIds: Set<String>,
    private val generation: Long,
    private val live: suspend (Collection<String>) -> Set<String>,
    private val gate: () -> DataOpGate = { DataOpGate.shared },
) : DispatchGuard {
    /** A photo of the batch was forgotten (or everything was deleted) before the call left: nothing was uploaded. */
    class Forgotten : java.io.IOException("photo forgotten before send")

    private val gone = Collections.synchronizedSet(mutableSetOf<String>())
    val dropped: Set<String> get() = synchronized(gone) { gone.toSet() }
    @Volatile var wiped = false
        private set

    override suspend fun <T> send(call: suspend () -> T): T = gate().dispatch {
        if (MemoryWipe.generation.get() != generation) { wiped = true; PcLog.i(TAG, "dispatch_dropped reason=wiped"); throw Forgotten() }
        val missing = frameIds - live(frameIds)
        if (missing.isNotEmpty()) { gone += missing; PcLog.i(TAG, "dispatch_dropped reason=forgotten frames=${missing.size}"); throw Forgotten() }
        call()
    }

    companion object {
        private const val TAG = "FrameDispatchGuard"
        fun forApp(app: Context, frameIds: Collection<String>, generation: Long) =
            FrameDispatchGuard(frameIds.toSet(), generation, { ids -> PipelineRecords.framesByIds(app, ids.toList()).map { it.id }.toSet() })
    }
}
