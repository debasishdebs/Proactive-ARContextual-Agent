package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.PoseSnapshot
import com.rokid.context.capture.trace.StartSnapshot
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.Verification
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.TraceControl
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import java.io.File
import java.nio.file.Files

/** A trace as it sits on disk, read back through the frozen importer path (`TraceFiles.verify` and `ChunkCodec`). */
class Recorded(val manifest: Manifest, val verification: Verification, val events: List<TraceEvent>, val dir: File) {
    inline fun <reified T : TraceEvent> all(): List<T> = events.filterIsInstance<T>()
    val tags: List<String> get() = events.map { com.rokid.context.capture.trace.EventCodec.tag(it) }
}

object TraceTestKit {
    fun tempDir(): File = Files.createTempDirectory("imu-trace-test").toFile()

    fun readAll(root: File): List<Recorded> {
        val traces = File(root, "traces").listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return traces.map { dir ->
            val names = dir.list()!!.toSet()
            val (m, v) = TraceFiles.verify(names) { File(dir, it).readText() }.getOrThrow()
            val events = (0 until m.chunkCount).flatMap { i ->
                ChunkCodec.decode(TraceFiles.decodeFile(File(dir, TraceFiles.chunkFile(i)).readText()).getOrThrow()).getOrThrow().events
            }
            Recorded(m, v, events, dir)
        }.sortedBy { it.manifest.startElapsedMs }
    }

    val info = TraceInfo("a1b2c3d4e5f6", 0, null, PlanMode.SETTINGS, BudgetTier.T0)

    fun snapshot(sampleEvery: Int = 1) = StartSnapshot(
        settingsHash = "a1b2c3d4e5f6", ring = emptyList(), lastKeyframeAt = 0, keptAt = emptyList(), gateLastKeptAt = null, gateKeptAt = emptyList(),
        lastBriefAt = 0, stillStep = 0, lastCheckHash = null, darkStreak = 0, bindBackoffUntil = null, coldStartGrace = false, bindFailures = 0, prevGyroMs = null,
        startups = emptyList(), pose = PoseSnapshot.ZERO, lastPhotoAttemptAtMs = 0, planGen = 0, planHash = null, glance = GlanceParams(),
        ctx = ContextState.UNKNOWN, resting = false, tier = BudgetTier.T0, sampleEvery = sampleEvery, exact = sampleEvery == 1,
    )

    fun profile() = TraceProfile(CameraWake.SETTLE, 10_000, 30_000, 10_000, 60_000, 333, BudgetTier.T0, lowPower = false, heatSlow = false, quickLookMs = 1000, ctxCap = null, hard = false)

    fun start(sampleEvery: Int = 1, info: TraceInfo = TraceTestKit.info) = TraceStart(snapshot(sampleEvery), profile(), GateParams(), info)

    fun collect(install: String = "inst-1", counter: Long = 0, until: Long? = null, atMs: Long = 1_000) =
        TraceControl(collect = true, collectUntilMs = until, installId = install, deletionCounter = counter, atMs = atMs)
}

/** A clock a test sets by hand (or an auto-advancing one for contention tests). */
class ManualClock(var t: Long = 100_000) : () -> Long { override fun invoke() = t }
