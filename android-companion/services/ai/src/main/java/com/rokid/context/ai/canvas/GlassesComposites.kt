package com.rokid.context.ai.canvas

import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import kotlinx.serialization.json.JsonPrimitive

/**
 * Composite phase C: the glasses lane (capture) nodes. Every flow is a DESCRIPTION of code that already runs on the glasses
 * (`GlanceDetector`, `GlanceAdmission`, `KeyframeGate`, `ContextPolicies`, `ContextFusion`), never executed and never read by [com.rokid.context.ai.canvas.plan.PlanCompiler]:
 * the exposed settings are params the compiler already folds into the plan, at the plan's own defaults, so no compiled plan or hash can change.
 */

/**
 * `policy.capture_rate` ("Capture frequency by activity"): the context (the phone's fusion, whose walking veto comes first and is not tunable) picks one
 * ROW; the row sets the cap per minute and the four timer photo gaps. REST, VEHICLE_PASSENGER, VEHICLE_DRIVING and FLIGHT are the editable rows
 * (each exposes its five numbers as the cells of the node's `rows` table); WALKING and UNKNOWN are locked: they keep today's capture.
 * The force settle and nudges columns stay in the inspector table.
 */
object CaptureRateComposite {
    private val COLS = listOf("capPerMin", "settledGapS", "quietGapS", "walkGapS", "stillCapGapS")

    /** The value of one cell in today's plan, in the table's own unit (gaps in seconds); null = blank (cap unchanged, gap inherit). */
    private fun today(row: PlanState, col: String): JsonPrimitive? {
        val r = PlanResolver.identity().policy.getValue(row)
        val v: Long? = when (col) {
            "capPerMin" -> r.capPerMin?.toLong()
            "settledGapS" -> r.timerPhotoGapsMs.settledMs / 1000
            "quietGapS" -> r.timerPhotoGapsMs.quietMs / 1000
            "walkGapS" -> r.timerPhotoGapsMs.walkMs / 1000
            else -> r.timerPhotoGapsMs.stillCapMs / 1000
        }
        return if (v == null || (col != "capPerMin" && v == 0L)) null else JsonPrimitive(v)
    }

    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("trigger", "Trigger in", "source"),
            InnerNode("ctx", "Context from phone", "source"),
            InnerNode("veto", "Walking veto (locked)", "op"),
            InnerNode("state", "Which activity?", "op"),
        ) + PlanState.entries.map { InnerNode(it.name, it.name, "op") } + listOf(
            InnerNode("WALKING", "WALKING (locked)", "op"),
            InnerNode("UNKNOWN", "UNKNOWN (locked)", "op"),
            InnerNode("out", "Trigger out", "branch"),
        ),
        wires = listOf(InnerWire("ctx", "veto"), InnerWire("veto", "state"), InnerWire("trigger", "state")) +
            (PlanState.entries.map { it.name } + listOf("WALKING", "UNKNOWN")).flatMap { listOf(InnerWire("state", it), InnerWire(it, "out")) },
        exposed = PlanState.entries.flatMap { s -> COLS.map { c -> InnerExposed("rows", s.name, s.name, c, today(s, c)) } },
        note = listOf(
            "Walking beats every other activity (the phone's fusion decides, not tunable); WALKING and UNKNOWN keep today's capture and are locked.",
            "A gap spaces timer photo attempts only; a glance capture stays immediate. A blank cap is unchanged, a blank gap inherits the settings profile.",
            "Force settle and nudges per activity are edited in the inspector table.",
        ),
    )
}
