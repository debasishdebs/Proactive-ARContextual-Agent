package com.rokid.context.glasses.capture.parity

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.replay.CaptureSimulator
import com.rokid.context.capture.replay.ReplayCandidate
import com.rokid.context.capture.replay.ReplayResult
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.Inexact
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.CameraWake
import com.rokid.context.glasses.capture.golden.DecisionGoldenRecorder
import com.rokid.context.glasses.capture.golden.GoldenScript
import com.rokid.context.glasses.capture.golden.GoldenScripts
import com.rokid.context.glasses.capture.plan.ActiveConfig
import com.rokid.context.glasses.capture.plan.PlanController
import com.rokid.context.glasses.capture.plan.PlanTestKit
import com.rokid.context.glasses.capture.plan.RigPlan
import com.rokid.context.glasses.capture.plan.ShadowCounts
import com.rokid.context.glasses.capture.plan.ShadowRunner
import com.rokid.context.glasses.capture.trace.Recorded
import com.rokid.context.glasses.capture.trace.TraceRecorder
import com.rokid.context.glasses.capture.trace.TraceTestKit
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Slice 3a Task 23a (M1 integration, code part): the real [com.rokid.context.glasses.capture.session.SamplingSession] over Task 1's scripts with the Task 16
 * recorder beside it, replayed by the Task 20 [CaptureSimulator].
 *
 * Replay is APPROXIMATE (controller ruling; the trace format is frozen): gates must be equal where every input is recorded, opens match by cause within
 * 250 ms, and the windows the rule excludes (`captureNow`, bind failure or back-off, async dwell/onset episodes) are counted, never compared.
 */
class TraceParityTest {
    private val roots = ArrayList<File>()
    @AfterTest fun cleanup() { roots.forEach { it.deleteRecursively() } }

    private class Run(val text: String, val traces: List<Recorded>, val shadow: ShadowCounts?)

    private fun golden(name: String) = javaClass.getResourceAsStream("/golden/decisions-$name.txt")!!.bufferedReader().use { it.readText() }
    private val identity: CapturePlan = PlanTestKit.plan()

    /** One script through the real session with a collecting recorder tapped on the classifier; [plan] runs against the rig before the session starts. */
    private fun record(s: GoldenScript, plan: ((com.rokid.context.glasses.capture.session.Rig) -> PlanController)? = null, shadow: Boolean = false): Run {
        val root = TraceTestKit.tempDir().also { roots += it }
        var text = ""; var counts: ShadowCounts? = null
        runTest(timeout = 15.minutes) {
            var ids = 0
            val rec = TraceRecorder(
                root, { testScheduler.currentTime }, { 1_700_000_000_000 + testScheduler.currentTime }, { "7" }, 1, io = { it.run() },
                newTraceId = { "trace-%04d-parity".format(++ids) },
            )
            rec.onControl(TraceTestKit.collect())
            var runner: ShadowRunner? = null
            text = DecisionGoldenRecorder.record(
                this, s,
                planFor = plan?.let { f -> { r -> f(r).also { c -> if (shadow) runner = ShadowRunner({ c.shadow }, { r.applied }, { r.session.status.value.restingUntilMs != null }).also { r.motion.addTap(it) } } } },
                onRig = { r -> r.motion.addTap(rec) }, trace = rec,
            )
            counts = runner?.take()
        }
        return Run(text, TraceTestKit.readAll(root), counts)
    }

    private fun replay(traces: List<Recorded>, drop: (TraceEvent) -> Boolean = { false }, candidate: ReplayCandidate? = null): List<ReplayResult> =
        traces.map { CaptureSimulator.replay(it.events.asSequence().filterNot(drop), it.manifest, candidate) }

    private fun reasons(rs: List<ReplayResult>) = rs.flatMap { it.inexactReasons.keys }.toSet()

    /**
     * The real session, then its own recording replayed. Where the inputs are fully recorded the gate decisions are EQUAL and each recorded open is matched by
     * cause within 250 ms (a mismatch of either kind is a failed window); the windows the rule excludes are only those it allows and are counted.
     */
    @Test fun sessionRecordingReplaysAsRecordedWithinTheApproximateRule() {
        var passed = 0; var failed = 0; var inexact = 0; var opens = 0; var traces = 0
        val seen = HashSet<Inexact>()
        for (s in GoldenScripts.all) {
            val run = record(s)
            assertTrue(run.traces.isNotEmpty(), "${s.name}: the recorder produced traces")
            assertTrue(run.traces.all { it.verification.reason == "ok" && it.manifest.exact }, "${s.name}: valid and exact traces")
            val rs = replay(run.traces)
            for ((t, r) in run.traces.zip(rs)) assertEquals(0, r.failed, "${s.name} ${t.manifest.startElapsedMs}: failed windows; ${r.mismatches.take(5)}")
            // inexact windows only for what the rule allows, and only where the script really has it
            if (Inexact.CAPTURE_NOW in reasons(rs)) assertTrue(s.name.startsWith("capture_now"), "${s.name}: captureNow windows only in the captureNow script")
            if (Inexact.BIND_FAILURE in reasons(rs)) assertTrue(s.name.startsWith("bind_backoff"), "${s.name}: bind windows only in the bind script")
            seen += reasons(rs)
            passed += rs.sumOf { it.passed }; failed += rs.sumOf { it.failed }; inexact += rs.sumOf { it.inexact }; traces += rs.size
            opens += run.traces.sumOf { t -> t.all<TraceEvent.Episode>().count { it.open } }
        }
        assertTrue(passed > 0, "the pass/fail set is not empty (passed=$passed)")
        assertTrue(opens > 0 && traces > 0)
        println("TraceParity: traces=$traces passed=$passed failed=$failed inexact=$inexact recordedOpens=$opens reasons=$seen")
    }

    /** The mutation behind the test above: a Score event dropped from a recording the replay gets must make the replay disagree. */
    @Test fun droppingAScoreEventFailsTheReplay() {
        val run = record(GoldenScripts.unknownWalk)
        assertEquals(0, replay(run.traces).sumOf { it.failed }, "the unmutated recording replays clean")
        var dropped = false
        val rs = replay(run.traces, drop = { e -> (e is TraceEvent.Score && !dropped).also { if (it) dropped = true } })
        assertTrue(dropped)
        assertTrue(rs.sumOf { it.failed } > 0 || rs.sumOf { it.mismatchCount } > 0, "dropping one sc: failed=${rs.sumOf { it.failed }} mismatches=${rs.sumOf { it.mismatchCount }}")
    }

    /** The identity plan applied (the TRIAL path, the only live one) with the recorder running: the decisions are the frozen settings goldens. */
    @Test fun identityPlanSessionEqualsSettingsGoldens() {
        for (s in GoldenScripts.all) {
            val run = record(s, plan = { r -> RigPlan.controller(r) { it.onMessage(PlanTestKit.trial(1, identity, 3_600_000)) } })
            assertEquals(golden(s.name), run.text, "identity plan with the recorder on: ${s.name}")
            assertTrue(run.traces.isNotEmpty(), "${s.name}: and it recorded")
        }
    }

    /**
     * The SHADOW counter (identity plan, would-open glances) against the counterfactual replay of the recording under the same config: the glance opens (DWELL,
     * ONSET) the replay decides. The shadow is a point estimate by design (spec L1: it counts every admitted glance, whatever the camera is doing), so the
     * tolerance is the brief's: within 5 % of the counterfactual over all scripts. A plan is always SETTLE (S27), so the candidate forces the SETTLE wake
     * (`ActiveConfig.forceSettle`); the tolerance is taken over the SETTLE-wake scripts, see below.
     */
    @Test fun shadowCountsEqualCounterfactualOpens() {
        val cfg = ActiveConfig.of(identity)
        var sh = 0; var cf = 0
        for (s in GoldenScripts.all) {
            val run = record(s, plan = { r -> RigPlan.controller(r) { it.onMessage(PlanTestKit.shadow(1, identity)) } }, shadow = true)
            val rs = replay(run.traces, candidate = ReplayCandidate("f5782e0d8610", glance = cfg.glance, gate = cfg.gate, forceSettle = cfg.forceSettle))
            val glance = rs.sumOf { r -> r.opens.count { it.cause == EpisodeCause.DWELL || it.cause == EpisodeCause.ONSET } }
            println("shadow ${s.name}: wake=${s.settings.cameraWake} shadowOpen=${run.shadow!!.open} counterfactualGlanceOpens=$glance byCause=${rs.flatMap { it.opens }.groupingBy { it.cause }.eachCount()}")
            // MOTION-wake scripts stay out: the replay follows the RECORDED episodes (a continuous STREAM episode is open through the glances), so a candidate
            // that would close the camera cannot add the glance opens it would make. forceSettle changes the profile, not the recorded episodes (finding, reported).
            if (s.settings.cameraWake != CameraWake.SETTLE) continue
            sh += run.shadow!!.open; cf += glance
        }
        assertTrue(cf > 100, "enough glance opens to compare: $cf")
        assertTrue(kotlin.math.abs(sh - cf) <= 0.05 * cf, "shadow=$sh counterfactual=$cf")
    }
}
