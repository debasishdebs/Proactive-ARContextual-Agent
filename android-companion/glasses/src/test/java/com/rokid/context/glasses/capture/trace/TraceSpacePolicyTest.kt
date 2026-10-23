package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.TraceChunk
import com.rokid.context.glasses.log.DiagFlagState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LGC7-min (plan 3.7): the basic space policy at `wantsStart` / `start` / `writeBatch` / `seal`. The volume is fake: free bytes are `capacity - bytes under root`, or a fixed number,
 * and every number of the policy is the real one unless a test says otherwise (the small policy keeps files of a few KB meaningful).
 */
class TraceSpacePolicyTest {
    private val root = TraceTestKit.tempDir()
    private val clock = ManualClock()
    private var ids = 0
    private val logs = CopyOnWriteArrayList<String>()
    private val wall = 1_700_000_000_000
    private var capacity = Long.MAX_VALUE / 4
    private var fixedFree: Long? = null
    private val executors = ArrayList<java.util.concurrent.ExecutorService>()
    @AfterTest fun cleanup() { executors.forEach { it.shutdownNow() }; root.deleteRecursively() }

    private fun dirBytes(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirBytes(it) }
    private val freeSeam: () -> Long = { fixedFree ?: (capacity - dirBytes(root)).coerceAtLeast(0) }
    private fun free() = freeSeam()
    /** The volume has exactly [n] bytes free right now, whatever is written afterwards (the capacity follows the files). */
    private fun freeIs(n: Long) { capacity = n + dirBytes(root) }

    /** Small numbers: a chunk file here is about 1 to 3 KB, so these are a few chunks. */
    private val small = SpacePolicy(floorBytes = 20_000, sealReserveBytes = 10_000, initialWriteBytes = 6_000, softBudgetOverride = Long.MAX_VALUE / 4)
    private fun policy(floor: Long = 20_000, reserve: Long = 10_000, budget: Long = Long.MAX_VALUE / 4) =
        SpacePolicy(floorBytes = floor, sealReserveBytes = reserve, initialWriteBytes = 6_000, softBudgetOverride = budget)

    private fun recorder(
        space: SpacePolicy = small, io: (Runnable) -> Unit = { it.run() }, deleteTree: (File) -> Boolean = { it.deleteRecursively() }, diag: Boolean = false,
    ) = TraceRecorder(
        root, clock, { wall + clock.t }, { "7" }, 1, io = io, newTraceId = { "trace-%04d-test".format(++ids) }, space = space, freeBytes = freeSeam,
        flushBytes = 3_000, log = { logs += it }, deleteTree = deleteTree, diagEnabled = { diag },
    )
    private fun TraceRecorder.begin() = start(TraceTestKit.start())
    private fun TraceRecorder.gyro(n: Int) = repeat(n) { clock.t += 20; onGyro(0.1f, -0.2f, 0.3f, clock.t - 5, MotionState.STILL) }
    private fun epoch() { File(root, "trace_epoch").writeText("inst-1\n0") }

    /** A sealed trace written through the real storage path: one or more chunks, `publish`, then (optionally) the transfer marker. */
    private fun sealTrace(id: String, endWall: Long, transferred: Boolean = false, chunks: Int = 1): Long {
        epoch()
        val st = TraceStorage(root)
        val ev0 = 50L * chunks
        fun mf(n: Int, ev: Long, raw: Long, shas: List<String>) = Manifest(
            traceId = id, installId = "inst-1", deletionCounter = 0, revision = 1, parentTraceId = null, bootId = Manifest.bootIdOf(7),
            startElapsedMs = 1_000, endElapsedMs = 1_000 + ev * 2, startWallMs = endWall - ev * 2, endWallMs = endWall,
            startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null,
            chunkCount = n, eventCount = ev, rawBytes = raw, traceSha256 = Manifest.traceSha(shas), recorded = TraceStatus.COMPLETE, closeReason = CloseReason.STOP, snapshot = TraceTestKit.snapshot(),
        )
        st.writeStart(id, mf(1, 1, 1, listOf("0".repeat(64))))
        var t = 1_000L; val shas = ArrayList<String>(); var ev = 0L; var raw = 0L
        for (seq in 0 until chunks) {
            val e = ChunkCodec.encode(id, seq, (0 until 50).map { t += 2; TraceEvent.Gyro(t, t - 1, 0.1f, 0.2f, 0.3f) })
            st.writeChunk(id, seq, TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = seq, count = 0, installId = "inst-1", deletionCounter = 0, sha256 = e.sha256, b64 = e.b64))
            shas += e.sha256; ev += e.eventCount; raw += e.rawBytes
        }
        assertEquals(ev0, ev)
        st.publish(id, mf(chunks, ev, raw, shas))
        if (transferred) assertTrue(st.markTransferred(id))
        return dirBytes(File(root, "traces/$id"))
    }

    private fun sealed(): Set<String> = File(root, "traces").list()?.toSet() ?: emptySet()
    private fun storage(space: SpacePolicy = small, deleteTree: (File) -> Boolean = { it.deleteRecursively() }, results: MutableList<ReclaimResult>? = null) =
        TraceStorage(root, space, freeSeam, onReclaim = { results?.add(it); logs += it.line() }, deleteTree = deleteTree)

    // ---- start ----

    /** The RED of LGC7-min: a start needs the bounded first write (chunk and skeleton) above the floor and the sealing reserve, not the 256 MiB trace cap; the seal needs only the physical room. */
    @Test fun StartsWithRoomForInitialBatchAndSealBelowStartHeadroom() {
        val p = SpacePolicy()
        epoch()
        fixedFree = 100L shl 20 // far below the 256 MiB per-trace cap, above floor + reserve + the initial write (about 80.1 MiB)
        val r = recorder(p); r.onControl(TraceTestKit.collect())
        assertTrue(r.wantsStart, "100 MiB free: room for the first chunk and the skeleton above the floor and the reserve")
        assertTrue(r.begin())
        r.gyro(300)
        fixedFree = 70L shl 20 // below the start headroom now (floor + reserve + initial), still above the floor
        r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded, "the closing batch and the seal need only physical room, not the start headroom")
        assertEquals("ok", t.verification.reason)
        assertFalse(r.wantsStart, "70 MiB is below floor + reserve + initial: the next start waits")
        assertEquals(TraceStartBlocker.DISK_FULL, r.startBlocker())
    }

    @Test fun RefusesStartBelowInitialWritePlusFloorAndReserve() {
        val p = SpacePolicy(); epoch()
        fixedFree = p.floorBytes + p.sealReserveBytes + p.initialWriteBytes - 1
        val r = recorder(p); r.onControl(TraceTestKit.collect())
        assertFalse(r.wantsStart); assertFalse(r.begin()); assertEquals(TraceStartBlocker.DISK_FULL, r.startBlocker())
        fixedFree = p.floorBytes + p.sealReserveBytes + p.initialWriteBytes
        clock.t += 6_000
        r.wantsStart // the boundary that retries: the writer re-measures
        assertTrue(r.wantsStart, "exactly the need: a trace may start"); assertTrue(r.begin())
    }

    @Test fun SoftBudgetIsTheSmallerOf1_5GBAnd20PercentOfFree() {
        val st = TraceStorage(root, SpacePolicy(), freeSeam)
        fixedFree = 1L shl 30; st.measureBudget(); assertEquals((0.2 * (1L shl 30)).toLong(), st.softBudgetBytes)
        fixedFree = 100L shl 30; st.measureBudget(); assertEquals(1_536L shl 20, st.softBudgetBytes)
    }

    @Test fun StateLinePrintsTheRealSoftBudget() {
        fixedFree = 2_000L shl 20; epoch()
        val r = recorder(SpacePolicy()); r.onControl(TraceTestKit.collect())
        val out = ArrayList<String>()
        TraceStateReporter(r, TraceBoundaryReporter(), { DiagFlagState.ENABLED }, clock, { _, l -> out += l }).poll()
        assertTrue(out.single().contains("softMB=400 "), out.single()) // 20 % of 2000 MiB, not the 1536 MiB maximum and not the old 150
    }

    // ---- reclaim order ----

    @Test fun ReclaimsTransferredBeforeOldest() {
        val a = sealTrace("trace-aaaa-0001", wall + 1_000)                       // oldest, not transferred
        sealTrace("trace-bbbb-0002", wall + 2_000, transferred = true)           // transferred, newer than a
        sealTrace("trace-cccc-0003", wall + 3_000)                               // newest
        val results = ArrayList<ReclaimResult>(); val st = storage(results = results)
        val need = 30_000L
        freeIs(small.floorBytes + need - 1)
        val r1 = st.reclaim(need)
        assertEquals(setOf("trace-aaaa-0001", "trace-cccc-0003"), sealed(), "the transferred one goes first although a is older")
        assertEquals(1, r1.transferred); assertEquals(0, r1.oldest); assertTrue(r1.freedBytes > 0)
        assertFalse(File(root, "transferred/trace-bbbb-0002").exists(), "its marker goes with it")
        freeIs(small.floorBytes + need - 1)
        val r2 = st.reclaim(need)
        assertEquals(setOf("trace-cccc-0003"), sealed()); assertEquals(0, r2.transferred); assertEquals(1, r2.oldest)
        assertTrue(a > 0)
        assertTrue(logs.contains("trace reclaim: transferred=1 oldest=0 freedMB=0 failed=0"), logs.toString())
    }

    @Test fun ReclaimOldestNotNewest() {
        sealTrace("trace-aaaa-0001", wall + 1_000); sealTrace("trace-bbbb-0002", wall + 2_000); sealTrace("trace-cccc-0003", wall + 3_000)
        val st = storage(); val need = 30_000L
        freeIs(small.floorBytes + need - 1); st.reclaim(need)
        assertEquals(setOf("trace-bbbb-0002", "trace-cccc-0003"), sealed(), "the oldest by endWallMs goes, never the newest")
        freeIs(small.floorBytes + need - 1); st.reclaim(need)
        assertEquals(setOf("trace-cccc-0003"), sealed())
    }

    @Test fun ReclaimKeepsTheNewestWhenOnlyTheBudgetIsCrossed() {
        sealTrace("trace-aaaa-0001", wall + 1_000); sealTrace("trace-bbbb-0002", wall + 2_000)
        val st = storage(policy(budget = 1)) // every byte is over the budget, but the physical room is plentiful
        st.reclaim(10_000)
        assertEquals(setOf("trace-bbbb-0002"), sealed(), "the budget alone never deletes the newest evidence")
    }

    @Test fun ReclaimFailureRetried() {
        sealTrace("trace-aaaa-0001", wall + 1_000, transferred = true)
        var fail = true; val results = ArrayList<ReclaimResult>()
        val st = storage(deleteTree = { if (fail) false else it.deleteRecursively() }, results = results)
        freeIs(small.floorBytes + 1_000 - 1)
        val r1 = st.reclaim(1_000)
        assertEquals(1, r1.failed); assertEquals(0, r1.transferred + r1.oldest); assertEquals(setOf("trace-aaaa-0001"), sealed())
        assertTrue(File(root, "transferred/trace-aaaa-0001").isFile, "a trace that is still there keeps its marker")
        fail = false
        val r2 = st.reclaim(1_000) // the next boundary
        assertEquals(0, r2.failed); assertEquals(1, r2.transferred); assertTrue(sealed().isEmpty())
        assertTrue(logs.any { it.startsWith("trace reclaim: ") && it.endsWith("failed=1") }, logs.toString())
    }

    @Test fun NeverDeletesRunningTrace() {
        sealTrace("trace-aaaa-0001", wall + 1_000)
        val st = storage()
        val running = "trace-run0-0001"
        st.writeStart(running, TraceStorageRunningFixture.manifest(running))
        st.writeChunk(running, 0, TraceStorageRunningFixture.chunk(running))
        freeIs(0) // nothing can be satisfied: the sealed trace goes, the running one stays
        st.reclaim(1_000_000)
        assertTrue(sealed().isEmpty())
        assertTrue(File(root, "imu_trace_tmp/$running/chunk-0000.json").isFile && File(root, "imu_trace_tmp/$running/start.json").isFile)
    }

    @Test fun ReclaimWaitsForAPendingCleanup() {
        sealTrace("trace-aaaa-0001", wall + 1_000, transferred = true)
        val st = storage()
        assertTrue(st.persistCleanup(false, "inst-1", 0, listOf("T trace-zzzz-0009")))
        freeIs(0)
        val r = st.reclaim(10_000)
        assertEquals(setOf("trace-aaaa-0001"), sealed(), "the cleanup fence: nothing is reclaimed under a pending cleanup")
        assertFalse(r.any)
        st.finishForget()
        st.reclaim(10_000)
        assertTrue(sealed().isEmpty())
    }

    // ---- mid-trace ----

    @Test fun CrossBudgetMidTraceKeepsWritingAndReclaims() {
        val s = sealTrace("trace-old0-0001", wall - 5_000_000, transferred = true)
        sealTrace("trace-old0-0002", wall - 4_000_000)
        epoch()
        val r = recorder(policy(budget = dirBytes(root) + small.startNeed + 4_000)); r.onControl(TraceTestKit.collect()) // the START fits the budget: only the writing crosses it
        assertTrue(r.begin())
        r.gyro(3_000)
        assertTrue(r.recording, "crossing the soft budget never stops the trace")
        assertFalse("trace-old0-0001" in sealed(), "the transferred trace was reclaimed on the writer")
        assertTrue("trace-old0-0002" in sealed(), "the newest evidence stays")
        r.close(CloseReason.STOP)
        val t = TraceTestKit.readAll(root).first { it.manifest.traceId == "trace-0001-test" }
        assertEquals(TraceStatus.COMPLETE, t.manifest.recorded, "nothing was dropped for the budget")
        assertEquals(CloseReason.STOP, t.manifest.closeReason)
        assertTrue(logs.any { it.startsWith("trace reclaim: transferred=1 ") }, logs.toString())
        assertTrue(s > 0)
    }

    @Test fun PhysicalExhaustionStopsOnlyThen() {
        epoch()
        val r = recorder(policy(budget = 1_000)) // a budget that is crossed at once, with nothing reclaimable
        r.onControl(TraceTestKit.collect()); assertTrue(r.begin())
        r.gyro(2_000)
        assertTrue(r.recording, "the soft budget alone does not stop recording")
        assertNull(r.startBlocker())
        freeIs(small.floorBytes + small.sealReserveBytes + 1_000) // less than one more chunk above the floor and the reserve
        r.gyro(4_000)
        assertFalse(r.recording, "physical exhaustion closes the trace")
        val t = TraceTestKit.readAll(root).single()
        assertEquals(CloseReason.CAP, t.manifest.closeReason)
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded, "the batches that did not fit were dropped, the rest is kept")
        assertTrue(t.manifest.chunkCount >= 1)
        assertEquals(TraceStartBlocker.DISK_FULL, r.startBlocker())
        val out = ArrayList<Pair<Boolean, String>>()
        TraceStateReporter(r, TraceBoundaryReporter(), { DiagFlagState.ENABLED }, clock, { w, l -> out += w to l }).poll()
        assertTrue(out.single().first && out.single().second.startsWith("trace state: blocker=disk_full "), out.toString())
    }

    @Test fun ResumesAutomaticallyAfterReclaim() {
        sealTrace("trace-old0-0001", wall - 5_000_000, transferred = true, chunks = 8)
        epoch()
        val r = recorder(); r.onControl(TraceTestKit.collect())
        assertTrue(r.begin()); r.gyro(200)
        freeIs(small.floorBytes + small.startNeed - 1) // one byte short of a start, before the closing batch and the seal take their share
        r.close(CloseReason.STOP)
        assertEquals(TraceStartBlocker.DISK_FULL, r.startBlocker())
        assertFalse(r.wantsStart, "no room right now (this poll queues the retry)")
        assertTrue(r.wantsStart, "the boundary retry reclaimed the transferred trace and the next start is allowed")
        assertFalse("trace-old0-0001" in sealed())
        assertTrue(r.begin())
    }

    @Test fun FloorIsKept() {
        epoch(); val p = policy(floor = 20_000, reserve = 10_000)
        val r = recorder(p); r.onControl(TraceTestKit.collect())
        freeIs(p.floorBytes + p.sealReserveBytes + 8_000); assertTrue(r.begin())
        r.gyro(6_000)
        assertFalse(r.recording)
        assertTrue(free() >= p.floorBytes, "the floor is never spent, not even by the closing batch or the seal: free=${free()}")
        assertTrue(TraceTestKit.readAll(root).single().manifest.chunkCount >= 1)
    }

    @Test fun SealingReserveIsKeptForTheClosingBatchAndTheSeal() {
        epoch(); val p = policy(floor = 0, reserve = 10_000)
        val r = recorder(p); r.onControl(TraceTestKit.collect())
        freeIs(10_000 + 12_000); assertTrue(r.begin())
        r.gyro(6_000)
        assertFalse(r.recording)
        val t = TraceTestKit.readAll(root).single()
        assertEquals(TraceStatus.PARTIAL, t.manifest.recorded) // batches that did not fit above the reserve were dropped; the trace itself verified and sealed
        assertTrue(t.all<TraceEvent.Close>().isNotEmpty(), "normal batches stopped above the reserve, so the closing batch and the seal still fit")
    }

    // ---- recovery ----

    /** A process killed mid-trace: the abandoned recorder leaves the trace in `imu_trace_tmp` with a start record and chunks. */
    private fun killedMidTrace(): String {
        epoch()
        val r = recorder(); r.onControl(TraceTestKit.collect()); assertTrue(r.begin())
        var guard = 0
        while (File(root, "imu_trace_tmp").listFiles()?.singleOrNull()?.list()?.any { it.startsWith("chunk-") && it.endsWith(".json") } != true && guard++ < 100_000) r.gyro(1)
        r.gyro(200)
        return File(root, "imu_trace_tmp").listFiles()!!.single().name
    }

    @Test fun RecoverySealsEvidenceThatNoLongerFitsTheShrunkBudget() {
        val id = killedMidTrace()
        recorder(policy(budget = 1)) // a restart with a tiny recomputed budget
        assertEquals(setOf(id), sealed(), "seal uses only the physical floor and the reserve")
        assertTrue(File(root, "imu_trace_tmp").listFiles().isNullOrEmpty())
        assertEquals(TraceStatus.PARTIAL, TraceTestKit.readAll(root).single().manifest.recorded)
    }

    @Test fun RecoveryOverBudgetSealsAndReclaimsOthers() {
        val id = killedMidTrace() // its wall clock is older than every sealed trace below
        sealTrace("trace-tttt-0001", wall + 10_000_000, transferred = true)
        sealTrace("trace-uuuu-0002", wall + 20_000_000)
        sealTrace("trace-vvvv-0003", wall + 30_000_000)
        recorder(policy(budget = 1))
        assertEquals(setOf(id, "trace-vvvv-0003"), sealed(), "the recovered trace is kept; the transferred and the older ones are reclaimed, the newest of the others stays")
        assertTrue(logs.any { it.startsWith("trace reclaim: transferred=1 oldest=1 ") }, logs.toString())
    }

    @Test fun RecoveryUnderPhysicalExhaustionKeepsTheDirectory() {
        val id = killedMidTrace()
        freeIs(small.floorBytes + 100) // not even the floor and a skeleton rewrite
        recorder()
        assertTrue(File(root, "imu_trace_tmp/$id").isDirectory, "no room is not a rejection: the evidence stays for a later recovery")
        assertTrue(sealed().isEmpty())
        freeIs(1L shl 30)
        recorder()
        assertEquals(setOf(id), sealed())
    }

    // ---- threads ----

    @Test fun NoMainThreadIo() {
        sealTrace("trace-old0-0001", wall - 5_000_000, transferred = true, chunks = 8)
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "space-writer").also { it.isDaemon = true } }.also { executors += it }
        val seen = CopyOnWriteArrayList<String>()
        val free = freeSeam
        val freeSpy: () -> Long = { seen += "free:" + Thread.currentThread().name; free() }
        val r = TraceRecorder(
            root, clock, { wall + clock.t }, { "7" }, 1, io = { exec.execute(it) }, newTraceId = { "trace-%04d-test".format(++ids) }, space = small, freeBytes = freeSpy,
            flushBytes = 3_000, log = {}, deleteTree = { seen += "delete:" + Thread.currentThread().name; it.deleteRecursively() },
        )
        fun idle() { val l = CountDownLatch(1); exec.execute { l.countDown() }; assertTrue(l.await(30, TimeUnit.SECONDS)) }
        idle(); r.onControl(TraceTestKit.collect()); idle()
        assertTrue(r.wantsStart); assertTrue(r.begin()); r.gyro(200); idle()
        freeIs(small.floorBytes + small.startNeed - 1) // squeezes the volume so the transferred trace has to be reclaimed
        r.close(CloseReason.STOP); idle()
        r.wantsStart; idle(); r.wantsStart
        idle()
        assertTrue(seen.any { it.startsWith("free:") } && seen.any { it.startsWith("delete:") }, seen.toString())
        assertTrue(seen.all { it.endsWith(":space-writer") }, "every volume and delete call ran on the storage thread: $seen")
        assertNotNull(Thread.currentThread())
    }
}

/** A minimal unfinished trace for the running-trace test. */
private object TraceStorageRunningFixture {
    fun manifest(id: String) = Manifest(
        traceId = id, installId = "inst-1", deletionCounter = 0, revision = 1, parentTraceId = null, bootId = Manifest.bootIdOf(7),
        startElapsedMs = 1_000, endElapsedMs = 1_000, startWallMs = 1_700_000_000_000, endWallMs = 1_700_000_000_000,
        startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null,
        chunkCount = 1, eventCount = 1, rawBytes = 1, traceSha256 = "0".repeat(64), recorded = TraceStatus.PARTIAL, closeReason = CloseReason.STOP, snapshot = TraceTestKit.snapshot(),
    )
    fun chunk(id: String): TraceChunk {
        val e = ChunkCodec.encode(id, 0, (0 until 5).map { TraceEvent.Gyro(1_000L + it * 2, 999L + it * 2, 0.1f, 0.2f, 0.3f) })
        return TraceChunk(traceId = id, kind = ChunkCodec.KIND, seq = 0, count = 0, installId = "inst-1", deletionCounter = 0, sha256 = e.sha256, b64 = e.b64)
    }
}
