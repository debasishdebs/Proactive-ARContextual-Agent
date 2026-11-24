package com.rokid.context.phone.canvas

import android.content.Context
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe

/** Slice 3a Task 31: the one [MarkRecorder] of the process (the hub's `pc_mark` and the voice interceptor share its listen/join state). */
object MarkGraph {
    @Volatile private var recorder: MarkRecorder? = null

    fun resetForTests() { recorder = null }

    fun recorder(app: Context): MarkRecorder = recorder ?: synchronized(this) {
        recorder ?: run {
            val ctx = app.applicationContext
            val epoch = DeletionEpochStore.of(ctx)
            MarkRecorder(
                MemoryGraph.get(ctx).db.canvasSlice3Dao(),
                fence = { DaoFence(MemoryWipe.generation.get(), epoch.read(), MemoryWipe.generation::get, epoch::read) },
                wallClock = System::currentTimeMillis,
                log = MarkRecorder.pcLog,
                onRecorded = { com.rokid.context.canvas.moment.MomentTriggers.get(ctx).onMarkRecorded(it) },
                onChanged = { com.rokid.context.canvas.moment.MomentTriggers.get(ctx).onMarkRecorded(it) },
                // an undone mark's photo goes through the one gated Forget path (tombstone, Moment cleanup, outbox); a running export refuses it and the undo is resent
                forgetPhotoOutcome = { frameId -> com.rokid.context.orchestration.forget.ForgetActions.forgetMomentKeepPinsOutcome(ctx, frameId) }, // the typed outcome is kept: a cached export left behind reaches the phone as the cache-left notice; // never deletes a pin (it only clears the link): an undo has no dialog that names pins (coverage map O6)
                cleanupOwed = { frameId -> com.rokid.context.orchestration.forget.ForgetActions.cleanupStillOwed(ctx, frameId) }, // the retry of an undo whose photo Forget committed with a cleanup notice keeps returning it until clean
                // v0.9.7 slice D: a phone-origin mark asks the glasses for its photo through the hub (read lazily: the hub is built on first use); the call tag uses the existing call detection
                phonePhoto = com.rokid.context.phone.hub.HubMarkPhoto(
                    connected = { com.rokid.context.phone.hub.HubGraph.get(ctx).carriesData() },
                    supportsRequest = { com.rokid.context.phone.hub.HubGraph.get(ctx).peerMarkPhoto },
                    worn = { com.rokid.context.phone.hub.HubGraph.get(ctx).wornNow() },
                    captureRunning = { com.rokid.context.phone.hub.HubGraph.get(ctx).captureRunning() },
                    callActive = com.rokid.context.phone.hub.HubGraph::callActive,
                    send = { ref -> com.rokid.context.phone.hub.HubGraph.get(ctx).requestMarkPhoto(ref) },
                ),
                callActive = com.rokid.context.phone.hub.HubGraph::callActive,
                deleteMarks = { ids -> com.rokid.context.memory.forget.lifecycle.ForgetExecutor.deleteMarks(MemoryGraph.get(ctx).db, ids) }, // forgetV2 only
            )
        }.also { recorder = it }
    }
}
