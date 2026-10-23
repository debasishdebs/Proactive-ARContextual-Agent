package com.rokid.context.canvas

import android.content.Context
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe

/**
 * The executors of experiment runs that the live/shadow interpreter must never have (S6 tier 3). `Executors.all()` is NOT edited: [all] is that map plus [extras], and the phone
 * reaches [extras] only through `CanvasBackendImpl.create(extraExecutors = ...)` (its `composeExecutors` adds them to the built-ins and refuses an overlap). `PipelineInterpreter`
 * keeps `Executors.all()`, so a `py.fn` node never runs in a live path.
 */
object ExperimentExecutors {
    /** Only the executors the built-ins do not have: today `py.fn`. [fenceNow] reads the fence a job is created under (the runner's capture is what a job carries). */
    fun extras(helper: HelperJobPort?, fenceNow: suspend () -> HelperFence): Map<String, NodeExecutor> {
        val py = PythonExecutor(helper, fenceNow)
        return mapOf(py.type to py)
    }

    /** The runner's whole map: the built-ins plus [extras]. */
    fun all(helper: HelperJobPort?, fenceNow: suspend () -> HelperFence): Map<String, NodeExecutor> = Executors.all() + extras(helper, fenceNow)

    /** The production binding: the fence is the in-process wipe generation, the persisted deletion epoch and `frame_forget_rev`, as the job service's DAO re-checks them. */
    fun forApp(app: Context, helper: HelperJobPort?): Map<String, NodeExecutor> {
        val dao = MemoryGraph.get(app).db.canvasSlice3Dao()
        val epoch = DeletionEpochStore.of(app)
        return extras(helper) { HelperFence(MemoryWipe.generation.get(), epoch.read(), dao.frameForgetRev()) }
    }
}
