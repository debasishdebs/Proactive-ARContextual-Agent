package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.CapturePlan
import com.rokid.context.capture.plan.PlanMeta
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task 23c regression on REAL worn traces. The traces are personal data and are never committed: the test runs only when `PCA_REAL_TRACES` lists trace directories
 * (colon separated, each a pulled `files/imu/traces/<id>` folder) and skips otherwise. The numbers pinned below were reproduced from them (delivery stalls modelled,
 * dwell/onset opens exact when they have a twin); a trace whose id is not pinned only prints its figures.
 */
class RealTraceReplayTest {
    private class Pin(val windows: Int, val passed: Int, val failed: Int, val inexact: Int, val baseOpens: Int, val identityOpens: Int, val stalls: Int)

    private val pins = mapOf(
        // 24.8 min worn walk, COMPLETE (after the Codex fix a stall excuses only its own resume decision: the 2 failing windows are a TIMER resume decision where the live loop opened a DWELL): 38 stalls (208 s). Before the stall model: baseline 154 opens, identity candidate 122, 24 windows compared (20 pass, 4 fail), 92 ASYNC_CALLBACK.
        "bc1f4b0e-b549-4361-b6d4-13a5ed61fda2" to Pin(116, 114, 2, 0, 117, 117, 38),
        // 98.8 min, PARTIAL: 189 stalls (561 s). Six windows fail (stall-resume decisions of another cause, and 3 extras not explained by this task).
        "0e9c2f76-0ba4-4db2-84e7-8831fe6b789c" to Pin(409, 401, 6, 2, 415, 417, 189),
    )

    @Test fun realTracesReplayAsRecordedAcrossDeliveryStalls() {
        val dirs = System.getenv("PCA_REAL_TRACES")?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue("PCA_REAL_TRACES not set", dirs.isNotEmpty())
        for (d in dirs) {
            val dir = File(d)
            val (manifest, _) = TraceFiles.verify(dir.list()!!.toSet()) { File(dir, it).readText() }.getOrThrow()
            val events = { (0 until manifest.chunkCount).asSequence().flatMap { i -> ChunkCodec.decode(TraceFiles.decodeFile(File(dir, TraceFiles.chunkFile(i)).readText()).getOrThrow()).getOrThrow().events.asSequence() } }
            val identity = ReplayCandidate.of(CapturePlan(configHash = "f5782e0d8610", config = PlanResolver.identity(), meta = PlanMeta("identity", 0)))
            val base = CaptureSimulator.replay(events(), manifest)
            val cand = CaptureSimulator.replay(events(), manifest, identity)
            val rep = CounterfactualReplay.run(events, manifest, identity)
            val recorded = base.recordedOpens.size
            println("REAL ${manifest.traceId} min=${"%.1f".format(rep.minutes)} windows=${base.passed + base.failed + base.inexact} pass=${base.passed} fail=${base.failed} inexact=${base.inexact} reasons=${base.inexactReasons}")
            println("REAL ${manifest.traceId} opens recorded=$recorded baseline=${base.opens.size} identity=${cand.opens.size} baselineBounds=[${rep.baseline.opensLow},${rep.baseline.opensHigh}] candidateBounds=[${rep.candidate!!.opensLow},${rep.candidate!!.opensHigh}]")
            println("REAL ${manifest.traceId} glance(DWELL+ONSET) baseline=${rep.baseline.glanceOpens} identity=${rep.candidate!!.glanceOpens} admissions baseline=${rep.baseline.glanceAdmissions} identity=${rep.candidate!!.glanceAdmissions} recordedDwell=${events().count { it is TraceEvent.Episode && it.open && it.cause == EpisodeCause.DWELL }} recordedOnset=${events().count { it is TraceEvent.Episode && it.open && it.cause == EpisodeCause.ONSET }}")
            println("REAL ${manifest.traceId} causes baseline=${base.opens.groupingBy { it.cause }.eachCount()} identity=${cand.opens.groupingBy { it.cause }.eachCount()}")
            println("REAL ${manifest.traceId} inexactMin=${"%.2f".format(rep.inexactMinutes)} (${"%.1f".format(rep.inexactSharePct)} %) stalls=${rep.stallCount} ${"%.0f".format(rep.stallSeconds)} s (${"%.1f".format(rep.stallSharePct)} %) mismatches=${base.mismatchCount} unobserved=${"%.1f".format(rep.unobservedOpensPct)} %")
            val pin = pins[manifest.traceId] ?: continue
            assertEquals(pin.windows, base.passed + base.failed + base.inexact, "windows")
            assertEquals(pin.passed, base.passed, "passed"); assertEquals(pin.failed, base.failed, "failed"); assertEquals(pin.inexact, base.inexact, "inexact")
            assertEquals(pin.baseOpens, base.opens.size, "baseline opens"); assertEquals(pin.identityOpens, cand.opens.size, "identity opens"); assertEquals(pin.stalls, rep.stallCount, "stalls")
        }
    }
}
