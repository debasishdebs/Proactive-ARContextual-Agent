package com.rokid.context.glasses.capture.golden

import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.plan.PlanController
import com.rokid.context.glasses.capture.session.CaptureProfile
import com.rokid.context.glasses.capture.session.HeatLevel
import com.rokid.context.glasses.capture.session.HeatPoller
import com.rokid.context.glasses.capture.session.Rig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Test-only. Drives the real [com.rokid.context.glasses.capture.session.SamplingSession] (through the shared [Rig]) with a
 * seeded script in SETTINGS mode (no plan: [ContextPolicies.DEFAULT] per scripted context state, applied the way
 * CaptureService does) and renders one text line per observable decision: `t kind cause detail`.
 *
 * Nothing here touches production code: decisions are observed from the fake camera (open and close events), the keyframe sink,
 * the session's own diagnostics counters (open causes, novelty / cap / same-scene skips, glances), the classifier's flows and
 * the session status. The sensors and a 20 ms poll run on one coroutine, so the output is a pure function of the script.
 */
object DecisionGoldenRecorder {
    private const val FEED_MS = 20L

    /** Same rule as S83's `reservedBoundMs` (plan Task 4); redefined here because this task adds no main code. */
    private fun reservedBoundMs(state: ContextState, resting: Boolean): Long =
        if (state in setOf(ContextState.REST, ContextState.VEHICLE_PASSENGER, ContextState.VEHICLE_DRIVING, ContextState.FLIGHT) ||
            (resting && state != ContextState.WALKING)) 300_000L else 60_000L

    /**
     * [planFor] (slice 3a Task 11): builds the plan controller for this rig and applies whatever plan the test wants BEFORE the session
     * starts; the rig then reads gate values and glance tunables from it and the rows below come from its active config, the way
     * CaptureService composes them. Null = the unmodified settings path of the frozen goldens.
     */
    suspend fun record(test: TestScope, s: GoldenScript, planFor: ((Rig) -> PlanController)? = null, onRig: ((Rig) -> Unit)? = null,
        /** When set, the scripted IMU feed obeys the registered rate (FULL 50 Hz, STILL 20 Hz, LOW 10 Hz) like the real feed: a rate decision changes the classifier's input. Null = every 20 ms (the frozen goldens). */
        sensorRate: (() -> com.rokid.context.glasses.capture.motion.SensorRate)? = null,
        /** True = the profile is composed the way CaptureService does for a plan (`ActiveConfig.profile`: forced SETTLE), not tier + row. */
        composeProfile: Boolean = false,
        /** Slice 3a Task 23a: the IMU trace recorder to run beside the session (the caller adds it as a classifier tap in [onRig]). Null = none (the frozen goldens). */
        trace: com.rokid.context.glasses.capture.trace.SessionTrace? = null,
    ): String {
        val plan = ScriptPlan().also { s.plan(Random(s.seed), it, s.durationMs) }
        val r = Rig(test, s.settings, work = s.workMin, rest = s.restMin, glance = GlanceParams(), trace = trace)
        r.scene = 1
        onRig?.invoke(r)
        val ctl = planFor?.invoke(r)
        if (ctl != null) r.activePlan = { ctl.active }
        fun applyCtx(c: ContextState) {
            val pol = ctl?.active?.policyFor(c) ?: ContextPolicies.of(c)
            r.profile = if (composeProfile && ctl != null) ctl.active.profile(r.settings.cameraWake, s.tier, c) else CaptureProfile.of(r.settings.cameraWake, s.tier).withContext(pol)
            r.contextCap = pol.capPerMin
            r.applied = c
        }
        applyCtx(ContextState.UNKNOWN)

        // scheduled script actions, in time order (stable)
        class Act(val at: Long, val run: () -> Unit)
        val acts = ArrayList<Act>()
        val out = StringBuilder()
        val ctxTimeline = ArrayList<Pair<Long, ContextState>>().apply { add(0L to ContextState.UNKNOWN) }
        val restTimeline = ArrayList<Pair<Long, Boolean>>().apply { add(0L to false) }
        fun line(t: Long, kind: String, cause: String, detail: String) { out.append(t).append(' ').append(kind).append(' ').append(cause).append(' ').append(detail).append('\n') }
        plan.ctx.forEach { (t, c) -> acts += Act(t) { applyCtx(c); ctxTimeline += t to c; line(t, "ctx", c.name, "-") } }
        plan.heat.forEach { (t, h) -> acts += Act(t) { r.heatReading = h } }
        plan.failOpens.forEach { (t, n) -> acts += Act(t) { r.camera.failOpens = n } }
        plan.busy.forEach { (t, b) -> acts += Act(t) { r.camera.busyOpens = b } }
        plan.captureNows.forEach { t -> acts += Act(t) { line(t, "now", "-", "accepted=${r.session.captureNow()}") } }
        plan.turns.forEach { tn -> tn.scene?.let { sc -> acts += Act(tn.atMs) { r.scene = sc } } }
        acts.sortBy { it.at } // stable: same-time actions keep insertion order
        var actIdx = 0

        val turns = plan.turns.sortedBy { it.atMs }
        var turnIdx = 0
        var lastFedAt = Long.MIN_VALUE / 2
        val noise = Random(s.seed xor 0x5DEECE66DL)
        val dwellAt = ArrayList<Long>()
        val dwellOpenLatencies = ArrayList<Long>()
        val okOpens = ArrayList<Long>()
        val attempts = ArrayList<Long>()          // S83 attempts: every ok open, and every open that failed while the camera was busy
        val episodes = ArrayList<LongArray>()     // [start, end] of each camera episode (end = Long.MAX_VALUE while open)
        val classifierTrace = StringBuilder()
        var nextFeat = 1_000L
        val openCauses = ArrayList<Pair<Long, String>>()

        test.backgroundScope.launch { r.motion.dwell.collect { dwellAt += r.clock(); line(r.clock(), "dwell", "-", "tMs=$it") } }

        var evIdx = 0; var keepIdx = 0
        val causeQueue = ArrayDeque<String>()
        var analysedAtOpen = 0
        var lastMotion = r.motion.state.value
        var lastStatus = ""
        var lastGap = -1L
        var lastHeat = HeatLevel.OK
        var lastResting = false
        val poller = HeatPoller(r.heat, r.clock)

        fun poll(t: Long) {
            val d = r.session.diagSnapshot()
            for ((k, v) in d.wakes) repeat(v) { causeQueue.addLast(k) }
            while (evIdx < r.camera.events.size) {
                val e = r.camera.events[evIdx++]
                val at = e.substringAfter('@').toLong()
                if (e.startsWith("open")) {
                    val cause = causeQueue.removeFirstOrNull() ?: "?"
                    val ok = r.camera.isOpen
                    line(at, "open", cause, if (ok) "-" else "failed")
                    analysedAtOpen = r.session.analysedFrames
                    openCauses += at to cause
                    if (ok) { okOpens += at; episodes += longArrayOf(at, Long.MAX_VALUE) }
                    val busyNow = plan.busy.lastOrNull { it.first <= at }?.second == true
                    if (ok || busyNow) attempts += at
                    if (cause == "dwell") dwellAt.lastOrNull { it <= at }?.let { dwellOpenLatencies += at - it }
                } else {
                    line(at, "close", "-", "analysed=${r.session.analysedFrames - analysedAtOpen}")
                    episodes.lastOrNull()?.let { if (it[1] == Long.MAX_VALUE) it[1] = at }
                }
            }
            while (keepIdx < r.keyframes.size) {
                val (at, k) = r.keyframes[keepIdx++]
                line(at, "keep", if (k.dwell) "dwell" else "other", "scene=${com.rokid.context.glasses.capture.session.Scenes.sceneOf(k.jpeg)} motion=${k.motion} blur=${k.blur.toInt()}")
            }
            if (d.novelSkips > 0) line(t, "skip", "novel", "n=${d.novelSkips}")
            if (d.capped > 0) line(t, "skip", "cap", "n=${d.capped}")
            if (d.same > 0) line(t, "skip", "same", "n=${d.same}")
            if (d.glances > 0 || d.micro > 0) line(t, "glance", "-", "g=${d.glances} micro=${d.micro}")
            val ms = r.motion.state.value
            if (ms != lastMotion) { lastMotion = ms; line(t, "motion", ms.name, "-") }
            val st = r.session.status.value
            val stKey = "running=${st.running} reason=${st.reason}"
            if (stKey != lastStatus) { lastStatus = stKey; line(t, "status", "-", stKey) }
            val resting = st.restingUntilMs != null
            if (resting != lastResting) { lastResting = resting; restTimeline += t to resting; line(t, "rest", if (resting) "start" else "end", "-") }
            val gap = r.session.stillGapMs()
            if (gap != lastGap) { lastGap = gap; line(t, "gap", "-", "ms=$gap step=${r.session.stillStep}") }
            val hl = r.heat.level.value
            if (hl != lastHeat) { lastHeat = hl; line(t, "heat", hl.name, "-") }
        }

        // sensors (gyro + linear accel at 50 Hz, heat poll) and the decision poll share one coroutine
        test.backgroundScope.launch {
            while (isActive) {
                val t = r.clock()
                while (actIdx < acts.size && acts[actIdx].at <= t) acts[actIdx++].run()
                while (turnIdx < turns.size && turns[turnIdx].atMs + turns[turnIdx].durMs <= t) turnIdx++
                val z = if (turnIdx < turns.size && turns[turnIdx].atMs <= t) turns[turnIdx].rate else 0f
                val walking = plan.walks.any { t in it }
                val gx = (noise.nextFloat() - 0.5f) * 0.04f; val gy = (noise.nextFloat() - 0.5f) * 0.04f; val gz = (noise.nextFloat() - 0.5f) * 0.04f
                val period = when (sensorRate?.invoke()) { null -> 0L; com.rokid.context.glasses.capture.motion.SensorRate.FULL -> 20L; com.rokid.context.glasses.capture.motion.SensorRate.STILL -> 50L; else -> 100L }
                val feedNow = sensorRate == null || t - lastFedAt >= period
                if (feedNow) lastFedAt = t
                if (feedNow) r.motion.onGyro(gx, gy, gz + z, t);
                val ax = (noise.nextFloat() - 0.5f) * 0.04f; val ay = (noise.nextFloat() - 0.5f) * 0.04f; val az = (noise.nextFloat() - 0.5f) * 0.04f
                val w = if (walking) (2.0 * sin(2 * PI * 1.8 * t / 1000.0)).toFloat() else 0f
                if (feedNow) r.motion.onLinearAccel(ax, ay, az + w, t);
                poller.tick()
                if (t >= nextFeat) {
                    nextFeat += 1_000L
                    val f = r.motion.features()
                    classifierTrace.append(t).append(' ').append((f.rotRad * 1000).toInt()).append(' ').append((f.gyroMax * 1000).toInt()).append(' ')
                        .append((f.accVariance * 1000).toInt()).append(' ').append((f.stepHz * 10).toInt()).append(' ').append(f.state).append(' ').append(f.glances).append(' ').append(f.micro).append('\n')
                }
                poll(t)
                delay(FEED_MS)
            }
        }
        r.session.start()
        delay(s.durationMs)
        poll(r.clock())
        r.session.stop("golden end")

        // expectedResv (S83 "while eligible"): sweep the 250 ms policy tick. A camera episode in progress satisfies the bound at every
        // tick it is open; so does an attempt (an ok open, or an open that failed while the camera was busy). The bound is taken from the
        // applied state at the tick being tested (not at the start of the gap). The scripts are always eligible (worn, unpaused, heat < STOP).
        fun at(x: Long): Pair<ContextState, Boolean> =
            (ctxTimeline.last { it.first <= x }.second) to (restTimeline.last { it.first <= x }.second)
        val resv = ArrayList<Long>()
        var last = 0L
        var ai = 0
        var x = 0L
        while (x <= s.durationMs) {
            while (ai < attempts.size && attempts[ai] <= x) { last = maxOf(last, attempts[ai]); ai++ }
            if (episodes.any { it[0] <= x && x < it[1] }) last = x
            val (c, rs) = at(x)
            if (x - last >= reservedBoundMs(c, rs)) { resv += x; last = x }
            x += 250L
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        val classifierSource = out.lineSequence().filter { l -> l.split(' ').getOrNull(1) in setOf("motion", "dwell", "glance") }.joinToString("\n") + "\n" + classifierTrace
        val classifierSha = sha.digest(classifierSource.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        val sb = StringBuilder()
        sb.append("# golden decisions v1 script=${s.name} seed=${s.seed} durationMs=${s.durationMs}\n")
        sb.append("# settings wake=${s.settings.cameraWake} tier=${s.tier} workRest=${s.settings.workRestEnabled} work=${s.workMin} rest=${s.restMin} glance=GlanceParams() mode=settings(ContextPolicies.DEFAULT)\n")
        sb.append("# line: t kind cause detail (t = virtual ms from session start)\n")
        sb.append("# expectedResv rule (S83 while eligible): swept on the 250 ms tick; an open camera episode satisfies the bound at every tick it is open;\n")
        sb.append("#   an attempt = an ok open, or an open that failed while the camera was busy (bind failures do not count); the bound is reservedBoundMs of the\n")
        sb.append("#   applied state at the tick tested (not at the gap start). Task 12 must add a resv open at each listed time, within ONE tick (250 ms), and nowhere else.\n")
        sb.append(out)
        sb.append("# classifierFingerprint=$classifierSha (sha256/16 of the motion, dwell and glance lines plus the classifier's own 1 s features(): rotation, gyro max, accel variance, step Hz, state, glances; changes if the classifier input stream or its output changes)\n")
        sb.append("# glanceTriggerToOpenLatencyMs=${dwellOpenLatencies.joinToString(",")}\n")
        sb.append("# opens=${openCauses.size} ok=${okOpens.size} byCause=${openCauses.groupingBy { it.second }.eachCount().toSortedMap()} keeps=${r.keyframes.size}\n")
        sb.append("# expectedResv=${resv.joinToString(",")}\n")
        return sb.toString()
    }
}
