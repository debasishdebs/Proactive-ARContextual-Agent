package com.rokid.context.canvas.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.PoseSnapshot
import com.rokid.context.capture.trace.StartSnapshot
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.ManifestChunk
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.TraceChunk
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import java.io.File

/** Writes a pulled trace directory (the frozen on-disk layout) for the import tests: one complete, valid trace of 13 events in one chunk. */
object PulledTraces {
    val events: List<TraceEvent> = listOf(
        TraceEvent.Ctx(1000, ContextState.REST), TraceEvent.Resting(1000, true),
        TraceEvent.Profile(1000, CameraWake.SETTLE, 10_000, 30_000, null, 60_000, 333, BudgetTier.T0, lowPower = false, heatSlow = true, quickLookMs = 3000, ctxCap = 3, hard = true),
        TraceEvent.GateParams(1000, 10, 24, true), TraceEvent.Plan(1000, 7, "abc123def456", PlanMode.SHADOW),
        TraceEvent.Gyro(1000, 990, 1.0f, -1.0f, 0.5f),
        TraceEvent.Episode(2000, true, EpisodeCause.TIMER),
        TraceEvent.Gate(2600, 42, GateOutcome.KEEP, "", encoded = false),
        TraceEvent.Episode(3000, false, EpisodeCause.DWELL, firstFrameAtMs = 2900, openedAtMs = 2100, outcome = EpisodeOutcome.OK),
        TraceEvent.PreRollEnd(11_000), TraceEvent.Close(12_000, "stop"),
    )
    private val glance = GlanceParams(0.6f, 0.3f, 250, 300, 0.26f, 1_500, 100, 0.05f, 20_000, 0.30f, 0.8f, 4, 2)
    private val snapshot = StartSnapshot(
        settingsHash = "a1b2c3d4e5f6", ring = emptyList(), lastKeyframeAt = null, keptAt = emptyList(), gateLastKeptAt = null, gateKeptAt = emptyList(), lastBriefAt = null,
        stillStep = 0, lastCheckHash = null, darkStreak = 0, bindBackoffUntil = null, coldStartGrace = false, bindFailures = 0, prevGyroMs = null, startups = emptyList(),
        pose = PoseSnapshot(0f, 0f, 0f, false, 0f, 0f, 0f, null), lastPhotoAttemptAtMs = null,
        planGen = 7, planHash = "abc123def456", glance = glance, ctx = ContextState.REST, resting = true, tier = BudgetTier.T0, sampleEvery = 1, exact = true,
    )

    /** Phone-clock evidence of a fixture manifest (all null = no phone offset, no samples: the trace cannot be mapped to the phone clock). */
    data class PhoneClock(
        val phoneOffsetMs: Long? = null, val offsetUncertaintyMs: Long? = null,
        val startPhoneWallMs: Long? = null, val endPhoneWallMs: Long? = null, val startPhoneAgeMs: Long? = null, val endPhoneAgeMs: Long? = null,
    )

    /** The fixture manifest: glasses wall 1_700_000_001_000 .. 1_700_000_012_010 (elapsed 1000 .. 12_000, so the wall offset drifts by 10 ms), [clock] as given. */
    fun manifest(id: String, installId: String, counter: Long, recorded: TraceStatus = TraceStatus.COMPLETE, clock: PhoneClock = PhoneClock(), evs: List<TraceEvent> = events): Manifest {
        val enc = ChunkCodec.encode(id, 0, evs)
        return Manifest(
            traceId = id, installId = installId, deletionCounter = counter, revision = 1, parentTraceId = null, bootId = "42", startElapsedMs = 1000, endElapsedMs = 12_000,
            startWallMs = 1_700_000_001_000, endWallMs = 1_700_000_012_010, startPhoneWallMs = clock.startPhoneWallMs, endPhoneWallMs = clock.endPhoneWallMs,
            startPhoneAgeMs = clock.startPhoneAgeMs, endPhoneAgeMs = clock.endPhoneAgeMs,
            phoneOffsetMs = clock.phoneOffsetMs, offsetUncertaintyMs = clock.offsetUncertaintyMs, chunkCount = 1, eventCount = evs.size.toLong(), rawBytes = enc.rawBytes.toLong(),
            traceSha256 = Manifest.traceSha(listOf(enc.sha256)), recorded = recorded, closeReason = "stop", snapshot = snapshot,
        )
    }

    /** Writes `<root>/<id>/` and returns the directory. [recorded] PARTIAL changes the manifest (so a re-import differs). */
    fun write(root: File, id: String, installId: String, counter: Long, recorded: TraceStatus = TraceStatus.COMPLETE, dirName: String = id, clock: PhoneClock = PhoneClock(), evs: List<TraceEvent> = events): File {
        val enc = ChunkCodec.encode(id, 0, evs)
        val m = manifest(id, installId, counter, recorded, clock, evs)
        val dir = File(root, dirName).apply { mkdirs() }
        File(dir, TraceFiles.MANIFEST_FILE).writeText(TraceFiles.encodeFile(ManifestChunk.encode(m)))
        val c = TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = 0, count = 1, installId = installId, deletionCounter = counter, sha256 = enc.sha256, b64 = enc.b64)
        File(dir, TraceFiles.chunkFile(0)).writeText(TraceFiles.encodeFile(c))
        return dir
    }
}
