package com.rokid.context.canvas

import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.db.CanvasDao
import com.rokid.context.orchestration.FlowPlans
import java.util.concurrent.ConcurrentHashMap

/**
 * The active `flow` publish of a component, compiled once per publish id. A version that cannot be read or no longer compiles gives null
 * (the built-in flow runs), never an exception for the caller to handle.
 */
class DaoFlowPlans(private val dao: CanvasDao) : FlowPlans {
    private val cache = ConcurrentHashMap<Long, java.util.Optional<FlowPlan>>()

    override suspend fun plan(kind: FlowPlan.Kind): FlowPlan? {
        val pub = dao.activeFlow(kind.component) ?: return null
        cache[pub.id]?.let { return it.orElse(null) }
        val compiled = dao.version(pub.pipelineId, pub.version)?.let { v -> PipelineJson.decode(v.json).getOrNull() }?.let(FlowPlan::compile)
        val plan = (compiled as? FlowPlan.Compiled.Ok)?.plan?.takeIf { it.kind == kind }
        if (plan == null) PcLog.w("DaoFlowPlans", "published flow pipeline is not runnable; built-in flow")
        cache[pub.id] = java.util.Optional.ofNullable(plan)
        return plan
    }
}
