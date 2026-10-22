package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.MAX_DARK_STREAK
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recorder alone (no session): window, buffering, epoch, writes, caps and the close reasons. The writer runs inline unless a test queues it. */
class TraceRecorderTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private val queue = ArrayDeque<Runnable>()
    private var inline = true
    private var ids = 0
    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun recorder(sampleEvery: Int = 1, cap: Long = TraceRecorder.DISK_CAP_BYTES, flush: Int = TraceRecorder.FLUSH_BYTES, bounds: TraceBounds = TraceBounds.DEFAULT, bufferEvents: Int = TraceRecorder.BUFFER_EVENTS) =
        TraceRecorder(
            root, clock, { 1_700_000_000_000 + clock.t }, { "7" }, sampleEvery, io = { r -> if (inline) r.run() else queue.addLast(r) },
            newTraceId = { "trace-%04d-test".format(++ids) }, diskCapBytes = cap, flushBytes = flush, bounds = bounds, bufferEvents = bufferEvents,
        )
    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }

    private fun TraceRecorder.begin(sampleEvery: Int = 1, info: TraceInfo = TraceTestKit.info): Boolean = start(TraceTestKit.start(sampleEvery, info))
    private fun TraceRecorder.gyro(n: Int, stepMs: Long = 20) = repeat(n) { clock.t += stepMs; onGyro(0.1f, -0.2f, 0.3f, clock.t - 5, MotionState.STILL) }

    // ---- window ----

    @Test fun recordsNothingUntilTheControlSaysCollect() {
        val r = recorder()
        assertFalse(r.wantsStart)
        assertFalse(r.begin(), "no control yet: no start")
        r.onControl(TraceTestKit.collect().copy(collect = false))
        assertFalse(r.wantsStart)
        r.onControl(TraceTestKit.collect())
        assertTrue(r.wantsStart)
        assertTrue(r.begin())
        assertTrue(r.recording)
        r.close(CloseReason.STOP)
        assertEquals(1, TraceTestKit.readAll(root).size)
    }

    @Test fun collectOffClosesTheTraceWithStop() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin(); r.gyro(10)
        r.onControl(TraceTestKit.collect().copy(collect = false))
        assertFalse(r.recording)
        val t = TraceTestKit.readAll(root).single()
        assertEquals("stop", t.manifest.closeReason)
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded)
    }

    @Test fun collectUntilExpiryClosesOnTheNextSampleOnTheGlassesClock() {
        val r = recorder()
        r.onControl(TraceTestKit.collect(until = 1_000 + 5_000, atMs = 1_000)) // 5 s window, measured on the phone's own two stamps
        r.begin()
        r.gyro(100, 20) // 2 s
        assertTrue(r.recording)
        r.gyro(200, 20) // past 5 s
        assertFalse(r.recording)
        assertEquals("stop", TraceTestKit.readAll(root).single().manifest.closeReason)
        assertFalse(r.wantsStart, "expired window: no new trace")
    }

    @Test fun eachSampleIsStampedInsideTheLockSoTheOrderIsTheAppendOrder() {
        val tick = AtomicLong(100_000)
        val r = TraceRecorder(root, { tick.incrementAndGet() }, { 1_700_000_000_000 }, { "7" }, 1, io = { it.run() }, newTraceId = { "trace-race-test" })
        r.onControl(TraceTestKit.collect()); r.begin()
        val threads = 4; val each = 20_000
        val go = CountDownLatch(1)
        val ts = (0 until threads).map { n -> Thread { go.await(); repeat(each) { i -> if (n % 2 == 0) r.onGyro(0.1f, 0f, 0f, 1_000L + i, MotionState.STILL) else r.onAccel(0f, 0.1f, 0f, 1_000L + i, 0, MotionState.STILL) } }.also { it.start() } }
        go.countDown(); ts.forEach { it.join() }
        r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single() // the frozen decoder refuses a tMs regression, so a read-back at all proves the order
        assertEquals("ok", t.verification.reason)
        assertEquals(threads * each + 7, t.events.size, "every sample, the five initial events, pr and cl")
        assertTrue(t.events.zipWithNext().all { (a, b) -> a.tMs <= b.tMs })
    }

    // ---- boundaries ----

    @Test fun traceStartsWithTheFiveInitialEventsAtTheStartTime() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin(); r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(listOf("cx", "rs", "pf", "gp", "pl", "cl"), t.tags)
        assertTrue(t.events.take(5).all { it.tMs == t.manifest.startElapsedMs })
        assertEquals("ok", t.verification.reason, "the frozen validator accepts it")
    }

    @Test fun preRollMarked() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        val start = clock.t
        r.gyro(400, 20) // 8 s: no pr yet
        r.close(CloseReason.STOP)
        assertEquals(0, TraceTestKit.readAll(root).single().all<TraceEvent.PreRollEnd>().size)
        root.deleteRecursively()
        val r2 = recorder(); r2.onControl(TraceTestKit.collect()); r2.begin()
        val s2 = clock.t
        r2.gyro(700, 20) // 14 s
        r2.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        val pr = t.all<TraceEvent.PreRollEnd>().single()
        assertEquals(s2 + TraceBounds.PRE_ROLL_MS, pr.tMs, "pr is exactly start + 10 s, whenever the first append at or after it came")
        assertEquals("ok", t.verification.reason)
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded)
        assertTrue(start > 0)
    }

    @Test fun preRollMarkedEvenWhenTheNextAppendIsFarPastIt() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        val s = clock.t
        clock.t += 30_000
        r.close(CloseReason.PAUSE) // the close itself is the first append after the pre-roll
        val t = TraceTestKit.readAll(root).single()
        assertEquals(listOf("cx", "rs", "pf", "gp", "pl", "pr", "cl"), t.tags)
        assertEquals(s + 10_000, t.all<TraceEvent.PreRollEnd>().single().tMs)
        assertEquals("ok", t.verification.reason)
    }

    @Test fun exactFalseWhenDownsampled() {
        val r = recorder(sampleEvery = 2); r.onControl(TraceTestKit.collect()); r.begin(sampleEvery = 2)
        r.gyro(10); repeat(10) { clock.t += 20; r.onAccel(0f, 0f, 9f, clock.t, clock.t, MotionState.STILL) }
        r.onBackoff(1); r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertFalse(t.manifest.exact)
        assertEquals(2, t.manifest.snapshot.sampleEvery)
        assertEquals(5, t.all<TraceEvent.Gyro>().size, "every 2nd gyro sample, the first kept")
        assertEquals(5, t.all<TraceEvent.Accel>().size)
        assertEquals(1, t.all<TraceEvent.Backoff>().size, "other events are never downsampled")
    }

    @Test fun exactTrueWhenEverySampleIsKept() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin(); r.gyro(10); r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertTrue(t.manifest.exact)
        assertEquals(10, t.all<TraceEvent.Gyro>().size)
    }

    @Test fun aStartWhoseSnapshotDisagreesWithTheRecorderSampleRateIsRefused() {
        val r = recorder(sampleEvery = 1); r.onControl(TraceTestKit.collect())
        assertFalse(r.begin(sampleEvery = 2))
    }

    // ---- events and bounds ----

    @Test fun darkStreakIsClamped() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        r.onDark(5_000_000); r.close(CloseReason.STOP)
        assertEquals(MAX_DARK_STREAK, TraceTestKit.readAll(root).single().all<TraceEvent.DarkStreak>().single().streak)
    }

    @Test fun aValueOutsideTheFrozenBoundsMarksThePartialInsteadOfThrowing() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        r.onScore(1, 0, 0, Float.NaN, false, MotionState.STILL, false, false, null, false, 5) // a non-finite blur cannot be encoded
        r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded)
        assertEquals(0, t.all<TraceEvent.Score>().size)
    }

    @Test fun nonFiniteSensorSamplesAreSkipped() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        r.onGyro(Float.NaN, 0f, 0f, 5, MotionState.STILL); r.onGyro(0f, 0f, 0f, -1, MotionState.STILL)
        r.close(CloseReason.STOP)
        assertEquals(0, TraceTestKit.readAll(root).single().all<TraceEvent.Gyro>().size)
    }

    @Test fun noEventIsRecordedBeforeOrAfterATrace() {
        val r = recorder(); r.onControl(TraceTestKit.collect())
        r.onBackoff(2); r.gyro(5) // before a start: ignored
        r.begin(); r.close(CloseReason.STOP)
        r.onBackoff(3); r.gyro(5) // after the close: ignored
        val t = TraceTestKit.readAll(root).single()
        assertEquals(listOf("cx", "rs", "pf", "gp", "pl", "cl"), t.tags)
    }

    @Test fun scoreCapMaxMeansUncapped() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        r.onScore(1, 5, 6, 80f, true, MotionState.STILL, true, false, null, true, 123)
        r.close(CloseReason.STOP)
        val s = TraceTestKit.readAll(root).single().all<TraceEvent.Score>().single()
        assertNull(s.cap); assertEquals(123, s.nowMs); assertTrue(s.hard)
    }

    // ---- writes ----

    @Test fun chunkFilesGoToTheTmpDirectoryThenTheWholeTraceIsRenamedWithItsManifestLast() {
        inline = false
        val r = recorder(flush = 2_000); r.onControl(TraceTestKit.collect()); r.begin()
        repeat(200) { r.gyro(1); drain() } // the writer keeps up
        val tmp = File(root, "imu_trace_tmp")
        val inTmp = tmp.listFiles()!!.single().list()!!.toList()
        assertTrue(inTmp.isNotEmpty() && inTmp.all { it.startsWith("chunk-") && it.endsWith(".json") }, "chunks so far: $inTmp")
        assertFalse(File(root, "traces").exists() && File(root, "traces").list()!!.isNotEmpty(), "not importable before the close")
        r.close(CloseReason.STOP); drain()
        assertTrue(tmp.listFiles()!!.isEmpty(), "tmp is empty after the rename")
        val t = TraceTestKit.readAll(root).single()
        assertEquals("ok", t.verification.reason)
        assertTrue(t.manifest.chunkCount > 1)
        assertTrue(File(t.dir, "manifest.json").exists())
    }

    @Test fun aBufferIsHandedToTheWriterAtAboutSixtyFourKilobytes() {
        inline = false
        val r = recorder(); r.onControl(TraceTestKit.collect()); drain(); r.begin()
        r.gyro(900) // ~50 KB estimated: below the flush point
        assertEquals(0, queue.size)
        r.gyro(500) // past 64 KB
        assertEquals(1, queue.size, "one batch queued, none flushed on a timer")
        drain(); r.close(CloseReason.STOP); drain()
        assertEquals(1400 + 7, TraceTestKit.readAll(root).single().manifest.eventCount.toInt())
    }

    @Test fun anOversizedChunkIsSplitInTwoAndRetried() {
        // A tiny b64 bound makes the real 90,000-char limit reachable: every chunk of more than a few events is refused and split.
        val small = TraceBounds(maxB64Chars = 450)
        val r = recorder(bounds = small, flush = 1_000_000); r.onControl(TraceTestKit.collect()); r.begin()
        r.gyro(300); r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertTrue(t.manifest.chunkCount > 4, "split into ${t.manifest.chunkCount} chunks")
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded)
        assertEquals(306, t.events.size, "no event lost by the split")
        assertEquals("ok", t.verification.reason)
        assertTrue(t.events.zipWithNext().all { (a, b) -> a.tMs <= b.tMs })
    }

    @Test fun aWriterThatFallsBehindNeverBlocksTheSensorPathAndMarksThePartial() {
        inline = false
        val r = recorder(flush = 4_000, bufferEvents = 128); r.onControl(TraceTestKit.collect()); r.begin()
        r.gyro(5_000) // returns at once although nothing is ever written
        r.close(CloseReason.STOP)
        drain()
        val t = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded)
        assertEquals("cl", t.tags.last(), "the close is never dropped")
    }

    @Test fun anUnwritableStorageNeverCrashesAndLeavesNoTrace() {
        val r = recorder(); r.onControl(TraceTestKit.collect())
        File(root, "imu_trace_tmp").writeText("a file where the directory should be") // after the epoch wipe: the writer cannot create its directory
        r.begin(); r.gyro(10)
        r.close(CloseReason.STOP)
        assertEquals(0, TraceTestKit.readAll(root).size)
    }

    @Test fun anUnfinishedTraceFromAKilledProcessIsDeletedAtStart() {
        File(root, "imu_trace_tmp/trace-dead-0001").mkdirs()
        File(root, "imu_trace_tmp/trace-dead-0001/chunk-0000.json").writeText("x")
        recorder()
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    // ---- caps ----

    @Test fun theDiskCapClosesTheTraceWithCapAndBlocksNewOnesWithoutDeletingAnything() {
        val r = recorder(cap = 20_000, flush = 3_000); r.onControl(TraceTestKit.collect()); r.begin()
        r.gyro(2_000)
        assertFalse(r.recording, "closed by the cap")
        assertFalse(r.wantsStart, "no new trace while the disk is full")
        val t = TraceTestKit.readAll(root).single()
        assertEquals("cap", t.manifest.closeReason)
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded, "nothing was dropped for the cap")
        assertEquals("ok", t.verification.reason)
    }

    // ---- epoch ----

    @Test fun newEpochDropsBufferAndTraces() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1))
        r.begin(); r.gyro(5); r.close(CloseReason.STOP)           // a finished trace of epoch 1
        r.begin(); r.gyro(50)                                      // a running trace with buffered samples
        assertEquals(1, TraceTestKit.readAll(root).size)
        r.onControl(TraceTestKit.collect(counter = 2))            // the phone deleted everything
        assertFalse(r.recording, "the running trace is dropped")
        assertEquals(0, TraceTestKit.readAll(root).size, "local traces are deleted")
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty(), "tmp is deleted")
        assertEquals("inst-1\n2", File(root, "trace_epoch").readText(), "the new epoch is adopted and persisted")
        r.begin(); r.gyro(3); r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(2, t.manifest.deletionCounter)
        assertEquals(0, t.all<TraceEvent.Gyro>().filter { it.sMs > 0 }.size - 3, "nothing of the dropped buffer leaks in")
    }

    // ---- forget (fix round 1) ----

    private fun TraceRecorder.oneTrace() { begin(); gyro(5); close(CloseReason.STOP) }
    private val wallBase get() = 1_700_000_000_000

    @Test fun aForgetRangeDeletesTheClosedTracesThatOverlapItAndKeepsTheRest() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1))
        r.oneTrace(); val first = TraceTestKit.readAll(root).single().manifest
        clock.t += 3_600_000; r.oneTrace()
        assertEquals(2, TraceTestKit.readAll(root).size)
        val atMs = wallBase + clock.t // the phone clock equals the glasses clock in this test
        r.onControl(TraceTestKit.collect(counter = 1).copy(atMs = atMs, forgetFromMs = first.startWallMs - 1_000, forgetToMs = first.endWallMs + 1_000, collect = true))
        val left = TraceTestKit.readAll(root)
        assertEquals(1, left.size); assertTrue(left.single().manifest.startWallMs > first.endWallMs + 60_000)
        assertFalse(File(root, "trace_forget").exists(), "the request is done: no pending file")
    }

    @Test fun aListedTombstoneIsDeletedByIdAndARunningOverlappingTraceIsDropped() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1))
        r.oneTrace(); val id = TraceTestKit.readAll(root).single().manifest.traceId
        r.begin(); r.gyro(5)
        val atMs = wallBase + clock.t
        r.onControl(TraceTestKit.collect(counter = 1).copy(atMs = atMs, tombstones = listOf(id), forgetFromMs = atMs - 1_000, forgetToMs = atMs + 1_000, collect = true))
        assertFalse(r.recording, "the running trace overlaps the range: dropped")
        assertEquals(0, TraceTestKit.readAll(root).size)
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
    }

    @Test fun aForgetThatCouldNotDeleteIsRetriedAtTheNextControl() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1))
        r.oneTrace(); val m = TraceTestKit.readAll(root).single()
        val tracesDir = File(root, "traces")
        val atMs = wallBase + clock.t
        val ctl = TraceTestKit.collect(counter = 1).copy(atMs = atMs, forgetFromMs = m.manifest.startWallMs - 1, forgetToMs = m.manifest.endWallMs + 1)
        tracesDir.setWritable(false); m.dir.setWritable(false)
        try { r.onControl(ctl) } finally { tracesDir.setWritable(true); m.dir.setWritable(true) }
        if (m.dir.exists()) {
            assertTrue(File(root, "trace_forget").exists(), "a failed delete leaves the request on disk")
            r.onControl(TraceTestKit.collect(counter = 1).copy(atMs = atMs)) // a plain control of the same epoch retries it
        }
        assertEquals(0, TraceTestKit.readAll(root).size); assertFalse(File(root, "trace_forget").exists())
    }

    @Test fun theNewEpochIsNotPersistedUntilItsWipeSucceededAndAnySameEpochControlRetries() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 1)); r.oneTrace()
        File(root, "imu_trace_tmp").mkdirs(); File(root, "imu_trace_tmp/x").writeText("x")
        val dir = File(root, "traces")
        dir.setWritable(false); File(root, "traces").listFiles()!!.forEach { it.setWritable(false) }
        try { r.onControl(TraceTestKit.collect(counter = 2)) } finally { dir.setWritable(true); File(root, "traces").listFiles()?.forEach { it.setWritable(true) } }
        if (File(root, "traces").exists()) {
            assertEquals("inst-1\n1", File(root, "trace_epoch").readText(), "not adopted on disk while the wipe failed")
            r.onControl(TraceTestKit.collect(counter = 2)) // the SAME (new) epoch again: retries
        }
        assertEquals(0, TraceTestKit.readAll(root).size)
        assertEquals("inst-1\n2", File(root, "trace_epoch").readText())
    }

    @Test fun newInstallIdResets() {
        val r = recorder(); r.onControl(TraceTestKit.collect(install = "inst-old", counter = 9))
        r.begin(); r.gyro(5); r.close(CloseReason.STOP)
        r.onControl(TraceTestKit.collect(install = "inst-new", counter = 0)) // a re-pairing: a LOWER counter, another install
        assertEquals(0, TraceTestKit.readAll(root).size)
        r.begin(); r.close(CloseReason.STOP)
        val m = TraceTestKit.readAll(root).single().manifest
        assertEquals("inst-new", m.installId); assertEquals(0, m.deletionCounter)
    }

    @Test fun theEpochSurvivesARestartAndAStaleControlIsIgnored() {
        val r = recorder(); r.onControl(TraceTestKit.collect(counter = 5)); r.begin(); r.close(CloseReason.STOP)
        val again = recorder()
        again.onControl(TraceTestKit.collect(counter = 4)) // older than the one adopted before the restart: stale, ignored entirely
        assertFalse(again.wantsStart)
        assertEquals(1, TraceTestKit.readAll(root).size, "and it deleted nothing")
        again.onControl(TraceTestKit.collect(counter = 5)) // the same epoch: no wipe
        assertEquals(1, TraceTestKit.readAll(root).size)
        assertTrue(again.wantsStart)
    }

    @Test fun aSessionWithoutAnInstallIdNeverStarts() {
        val r = recorder(); r.onControl(TraceTestKit.collect(install = ""))
        assertFalse(r.wantsStart)
    }

    // ---- config change closes the trace ----

    @Test fun aPlanActivationRevertOrSettingsEditClosesTheTrace() {
        val cases = listOf(
            TraceTestKit.info.copy(planGen = 3, planHash = "abc123", planMode = PlanMode.TRIAL) to "plan",
            TraceTestKit.info.copy(settingsHash = "ffffffffffff") to "settings",
        )
        for ((changed, reason) in cases) {
            root.deleteRecursively(); ids = 0
            val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
            assertFalse(r.onConfig(TraceTestKit.info), "unchanged: the trace goes on")
            assertTrue(r.onConfig(changed))
            assertEquals(reason, TraceTestKit.readAll(root).single().manifest.closeReason)
        }
        root.deleteRecursively(); ids = 0
        val live = TraceTestKit.info.copy(planGen = 3, planHash = "abc123", planMode = PlanMode.SHADOW)
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin(info = live)
        assertTrue(r.onConfig(TraceTestKit.info), "back to no plan = a revert")
        assertEquals("plan_revert", TraceTestKit.readAll(root).single().manifest.closeReason)
    }

    @Test fun episodeAndGateEventsKeepTheirFields() {
        val r = recorder(); r.onControl(TraceTestKit.collect()); r.begin()
        r.onEpisodeOpen(EpisodeCause.RESV)
        r.onScore(7, 1, 2, 90f, true, MotionState.WALKING, false, true, 4, false, 555)
        r.onGateKeep(7, true, 560)
        r.onGateSkip(8, "DUPLICATE")
        r.onFlush("episode", 570)
        r.onEpisodeClose(EpisodeCause.RESV, 540, 500, EpisodeOutcome.OK)
        r.onBlocked("not_worn"); r.onBlocked(null)
        r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(EpisodeCause.RESV, t.all<TraceEvent.Episode>().first().cause)
        assertEquals(560, t.all<TraceEvent.Gate>().first().keptAtMs)
        assertEquals("DUPLICATE", t.all<TraceEvent.Gate>()[1].reason)
        assertEquals(570, t.all<TraceEvent.Flush>().single().nowMs)
        assertNotNull(t.all<TraceEvent.Episode>().last().outcome)
        assertEquals("ok", t.verification.reason)
    }
}
