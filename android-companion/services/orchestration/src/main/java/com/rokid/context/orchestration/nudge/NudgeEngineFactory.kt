package com.rokid.context.orchestration.nudge

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.memory.MemoryGraph

/** The engine the periodic backstop and the fast path's evaluator both run. */
object NudgeEngineFactory {
    fun engine(app: Context): NudgeEngine {
        val memory = MemoryGraph.get(app)
        val sources = SourcesGraph.get(app)
        return NudgeEngine(
            NudgeInputs(memory, sources.provider), AiGraph.get(app).runner, NudgeGates(memory.db.nudgeGateDao(), { sources.settings.shownGapMin.value * 60_000L }) { sources.settings.nudgesPerDay.value },
            memory, com.rokid.context.orchestration.OriginFencedIntake(app, memory, ActionsGraph.get(app).intake),
            flows = com.rokid.context.canvas.DaoFlowPlans(memory.db.canvasDao()),
        )
    }
}
