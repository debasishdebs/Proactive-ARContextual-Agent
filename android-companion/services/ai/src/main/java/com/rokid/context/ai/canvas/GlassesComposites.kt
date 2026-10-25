package com.rokid.context.ai.canvas

import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
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

/**
 * `imu.head_motion` ("Head movement"). A VIEW of `GlanceDetector`: a bout starts when the gyro speed is above the onset, lasts while it stays above the release
 * (a gap up to the bridge does not end it), and counts only if it is long enough and turned far enough; a shorter bout is micro motion that never arms a glance.
 * All five numbers are plan parameters today (`glance.*`). A Threshold node folds into the onset, the bout length or the bout turn.
 */
object HeadMotionComposite {
    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("gyro", "Gyro speed", "source"),
            InnerNode("onset", "Bout starts", "op"),
            InnerNode("release", "Bout holds", "op"),
            InnerNode("bridge", "Bridge short gaps", "op"),
            InnerNode("long", "Long enough?", "op"),
            InnerNode("wide", "Turned enough?", "op"),
            InnerNode("bout", "Head bout", "branch"),
            InnerNode("micro", "micro motion", "branch"),
        ),
        wires = listOf(
            InnerWire("gyro", "onset"), InnerWire("onset", "release", "speed above"), InnerWire("release", "bridge"), InnerWire("bridge", "long", "bout ends"),
            InnerWire("long", "wide", "yes"), InnerWire("long", "micro", "no"), InnerWire("wide", "bout", "yes"), InnerWire("wide", "micro", "no"),
        ),
        exposed = listOf(
            InnerExposed("onsetRadS", "onset"), InnerExposed("releaseRadS", "release"), InnerExposed("bridgeMs", "bridge"),
            InnerExposed("minBoutMs", "long"), InnerExposed("minBoutRad", "wide"),
        ),
        note = listOf(
            "A bout only becomes a glance after the Settle step. A micro bout never starts a glance but postpones one that is waiting.",
            "A Threshold node on the chain sets the onset, the bout length or the bout turn; two that disagree are refused. The release may not exceed the onset.",
        ),
    )
}

/** `imu.settle`. A VIEW of `GlanceDetector.settled`: after a head bout (or after arriving from a walk) the head must stay quiet and still for the settle time before the glance fires. */
object SettleComposite {
    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("bout", "Head bout ended", "source"),
            InnerNode("quiet", "Quiet for", "op"),
            InnerNode("still", "Head still?", "op"),
            InnerNode("glance", "Glance (settled)", "branch"),
        ),
        wires = listOf(InnerWire("bout", "quiet"), InnerWire("quiet", "still", "time up"), InnerWire("still", "glance", "yes")),
        exposed = listOf(InnerExposed("settleMs", "quiet")),
        note = listOf(
            "Quiet means no gyro sample above the bout onset speed since the bout ended. Another real bout restarts the wait.",
            "Arriving after a walk also starts the wait.",
        ),
    )
}

/**
 * `gate.novelty`. A VIEW of the first test of `GlanceAdmission.decide`: a glance is skipped (NOVEL_SKIP) when the last photo is recent, the head
 * has turned less than the minimum since that photo, and no walk was seen since it. Any one of the three lets it through.
 */
object NoveltyComposite {
    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("glance", "Glance", "source"),
            InnerNode("window", "Last photo recent?", "op"),
            InnerNode("turn", "Turned enough?", "op"),
            InnerNode("walk", "Walked since?", "op"),
            InnerNode("new", "new view", "branch"),
            InnerNode("skip", "skip: same view", "branch"),
        ),
        wires = listOf(
            InnerWire("glance", "window"), InnerWire("window", "new", "older"), InnerWire("window", "turn", "within window"),
            InnerWire("turn", "new", "yes"), InnerWire("turn", "walk", "no"), InnerWire("walk", "new", "yes"), InnerWire("walk", "skip", "no"),
        ),
        exposed = listOf(InnerExposed("noveltyWindowMs", "window"), InnerExposed("noveltyMinRad", "turn")),
        note = listOf("The turn is the head rotation since the last photo was taken. A walk since the last photo always counts as a new view (fixed)."),
    )
}

/**
 * `gate.rate_cap`. A VIEW of `GlanceAdmission.decide` (second test) and then `KeyframeGate.finish`: once the photos kept in the last minute reach the cap (set by the activity row),
 * a glance may still go over it, up to twice the cap, only when the head turned a lot or a walk was seen (before the camera opens) AND the frame then differs enough from every
 * kept photo. Resting, and any context row with a cap, is a hard cap with no override.
 */
object RateCapComposite {
    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("glance", "Glance", "source"),
            InnerNode("cap", "At the cap?", "op"),
            InnerNode("turn", "Big head turn?", "op"),
            InnerNode("walk", "Walked since?", "op"),
            InnerNode("scene", "Big scene change?", "op"),
            InnerNode("ok", "may capture", "branch"),
            InnerNode("capped", "capped", "branch"),
        ),
        wires = listOf(
            InnerWire("glance", "cap"), InnerWire("cap", "ok", "under"), InnerWire("cap", "turn", "at cap"),
            InnerWire("turn", "scene", "yes"), InnerWire("turn", "walk", "no"), InnerWire("walk", "scene", "yes"), InnerWire("walk", "capped", "no"),
            InnerWire("scene", "ok", "yes"), InnerWire("scene", "capped", "no"),
        ),
        exposed = listOf(InnerExposed("bigTurnRad", "turn"), InnerExposed("bigChange", "scene")),
        note = listOf(
            "At the cap a glance needs a big head turn or a walk (decided before the camera opens), then a big scene change on the captured frame; never more than twice the cap.",
            "The cap itself is the activity row's cap per minute (Capture frequency by activity). Resting and rows with a context cap are hard caps: no override.",
            "A big scene change is the frame's hash distance from every kept photo, in bits of 64. Timer photos stop one below the cap with no override.",
        ),
    )
}

/**
 * `capture.image`. A VIEW of `KeyframeGate.offer/finish`: a frame below the blur floor is dropped; the rest are hashed (dHash and pHash) and compared with the ring of kept photos
 * (the last one, or all five); a frame is new when its smaller hash distance from every compared photo is above the limit; of several new candidates in a window the sharpest is kept.
 */
object CaptureImageComposite {
    val inner = InnerFlow(
        nodes = listOf(
            InnerNode("trigger", "Trigger in", "source"),
            InnerNode("blur", "Sharp enough?", "op"),
            InnerNode("hash", "Hash (dHash, pHash)", "op"),
            InnerNode("ring", "Compare with ring", "op"),
            InnerNode("distance", "Different enough?", "op"),
            InnerNode("keep", "keep sharpest", "branch"),
            InnerNode("drop", "drop", "branch"),
        ),
        wires = listOf(
            InnerWire("trigger", "blur"), InnerWire("blur", "hash", "yes"), InnerWire("blur", "drop", "blurry"), InnerWire("hash", "ring"), InnerWire("ring", "distance"),
            InnerWire("distance", "keep", "new"), InnerWire("distance", "drop", "same scene"),
        ),
        exposed = listOf(InnerExposed("ringCompare", "ring"), InnerExposed("hamming", "distance")),
        note = listOf(
            "Blur floor ${KeyframeGate.BLUR_FLOOR.toInt()} (fixed). While moving, up to ${KeyframeGate.MAX_CANDIDATES} candidates are collected for ${KeyframeGate.WINDOW_MS} ms and the sharpest new one wins.",
            "The ring holds the last 5 kept photos. Different means the smaller of the two hash distances is above the limit, in bits of 64.",
        ),
    )
}
