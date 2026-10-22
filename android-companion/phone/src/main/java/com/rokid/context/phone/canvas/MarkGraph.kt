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
                log = { android.util.Log.i("PcMarks", it) },
            )
        }.also { recorder = it }
    }
}
