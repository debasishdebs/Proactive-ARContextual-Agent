package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.CloseReason
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.StartSnapshot
import com.rokid.context.capture.trace.TraceBounds
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFormatException
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.capture.trace.clampDarkStreak
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.TraceChunk
import com.rokid.context.core.model.TraceControl
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.motion.SampleTap
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The glasses IMU trace recorder (slice 3a Task 16) on the FROZEN trace schema 1 (`:core:capture` trace package; nothing here changes it).
 *
 * **When it records.** Only while [recordingAllowed] (LGC4: diagnostics are on with a durable epoch, OR the phone's [TraceControl] says `collect` and `collectUntilMs` has not passed) AND the session offers a clean
 * boundary ([SessionTrace.wantsStart], [start]): the detector QUIET, no camera episode, an empty gate window, capture unblocked. A trace starts
 * with the initial `cx rs pf gp pl` events at its start time, then a 10 s pre-roll (`pr` at exactly start + 10 s). It ends (`cl`, manifest
 * `closeReason`) on collect off or expiry (`stop`), a privacy pause (`pause`), heat STOP (`stop`), a plan activation (`plan`), a plan revert
 * (`plan_revert`), a settings edit (`settings`), the disk or trace cap (`cap`), or session stop. A new segment starts at the next clean boundary.
 *
 * **Single writer, stamp inside the lock.** Every append goes through [lock]; `tMs` is `clock()` read INSIDE it, so stamp order equals append order
 * across the sensor thread, the camera analysis executor (score listener), the session tick and the link thread. A time with its own meaning
 * (the gate's `now`, a sensor time, the first frame) is a field. The `pr` event is the one exception by design: it carries `start + 10_000`
 * and is inserted before the first append at or after that instant, so order still holds.
 *
 * **Sensing is never slowed.** A sensor sample is a few array stores into a preallocated [EventBuf] (no allocation, no I/O, no timer, no wake-up).
 * At 64 KB of estimated payload the buffer is handed to the single writer thread ([io]); gzip, base64, hashing and file writes happen there.
 * If the writer falls behind ([MAX_IN_FLIGHT] buffers) the newest batch is dropped and the trace is recorded PARTIAL, never blocked.
 *
 * **Disk.** Chunk files go to `imu_trace_tmp/<id>/` (renamed from `.part`), the manifest is written last and the directory is renamed to
 * `traces/<id>/` at close ([TraceStorage]). **Space (LGC7-min, plan 3.7).** A trace starts when the volume holds its bounded first write (a chunk and the manifest
 * skeleton) above the 64 MB floor and the 16 MB sealing reserve; a later batch needs the floor, the reserve and itself, checked at the write. Crossing the soft budget
 * (min of 1.5 GB and 20 % of free space) reclaims on the writer thread (sealed traces with a transfer marker, then the oldest) and the trace keeps writing; only PHYSICAL
 * exhaustion after a reclaim closes it (`cap`, [TraceStartBlocker.DISK_FULL]), and the next boundary retries. The seal needs only the floor and the bounded rewrite. A chunk the codec refuses as too big
 * (`b64_too_long`: the real limit is 90,000 base64 characters) is split in two and retried.
 *
 * **Epoch (S57).** A [TraceControl] with a different `installId`, or a larger `deletionCounter`, drops the buffer and the running trace, deletes
 * every local trace and `imu_trace_tmp`, and adopts the epoch (persisted). A control with the same `installId` and a smaller counter is stale and ignored.
 *
 * **Forget (S58, P7).** A control's tombstones and forget range delete the closed traces they match (and drop a running one that overlaps); the request is kept in
 * `trace_forget` until the delete really succeeded, so a failed delete is retried at the next control. A failed epoch wipe is retried too, and the new epoch is persisted only after it.
 *
 * **Received cleanup (LGC8-min).** A control's epoch wipe and forget lines are persisted FIRST in `trace_cleanup_pending` (the single source of truth of [cleanupPending]), then
 * acted on; forget covers `imu_trace_tmp` too (not the live job). The record is rewritten as each part succeeds and removed only when all did. At process start it is REPLAYED
 * on the writer thread before [TraceStorage.recover] (which leaves tmp alone while a record still exists), so a killed trace in a forgotten range is deleted, never sealed.
 *
 * Not here: the transfer (`send`).
 *
 * Lock order: session, then classifier and gate, then [lock]. The recorder never calls back into any of them.
 */
class TraceRecorder(
    root: File,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val bootId: () -> String,
    /** 1 = every sensor sample (exact). n > 1 = every n-th gyro and accel sample (S66, flagged not exact). */
    override val sampleEvery: Int = 1,
    /** Runs a task on the single writer thread (tests: inline). Must run tasks in order. */
    private val io: (Runnable) -> Unit,
    private val newTraceId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** LGC7-min (plan 3.7): floor, sealing reserve, initial write bound and soft budget. */
    private val space: SpacePolicy = SpacePolicy(),
    /** Physical free bytes under [root]; called only on the writer thread (tests: a fake volume). */
    private val freeBytes: () -> Long = { volumeFreeBytes(root) },
    private val flushBytes: Int = FLUSH_BYTES,
    private val bounds: TraceBounds = TraceBounds.DEFAULT,
    private val bufferEvents: Int = BUFFER_EVENTS,
    private val log: (String) -> Unit = {},
    /** Test seams for an I/O error in the storage (the publication rename, the wipe's delete). */
    private val renameDir: (File, File) -> Boolean = { a, b -> a.renameTo(b) },
    private val deleteTree: (File) -> Boolean = { it.deleteRecursively() },
    /** LGC4: the single diagnostic-recording predicate (the persisted `diag_record` flag, effective only once the epoch is saved). Default: never. */
    private val diagEnabled: () -> Boolean = { false },
    /** LGC8-min: where the received-cleanup record lives (production: `files/trace_cleanup_pending`; default: next to the epoch file). */
    pendingFile: File = File(root, "trace_cleanup_pending"),
    /** Test seam: the atomic record writer for the cleanup record and the `trace_forget` working list (false = not saved). */
    writeRecord: (File, String) -> Boolean = com.rokid.context.glasses.log.DiagStateFile::atomicWrite,
    /** Test seam: reads a record file (the working forget list, manifests). */
    readFile: (File) -> String = { it.readText(Charsets.UTF_8) },
    /** LGC5: runs once on the writer thread right after the start-up recovery (the `trace boot:` line comes from it). A throw is contained. */
    private val onRecovered: (RecoveryInfo) -> Unit = {},
) : SessionTrace, SampleTap {
    init { require(sampleEvery in 1..StartSnapshot.MAX_SAMPLE_EVERY) { "sampleEvery" } }

    /** One trace being recorded; the writer-owned fields are touched only on the writer thread. */
    private class Job(
        val id: String, val startElapsed: Long, val startWall: Long, val startPhone: PhoneSample?, val snapshot: StartSnapshot,
        val installId: String, val counter: Long, val boot: String,
    ) {
        @Volatile var aborted = false
        @Volatile var dropped = false
        val inFlight = AtomicInteger(0)
        // writer-owned
        var seq = 0
        val shas = ArrayList<String>()
        var events = 0L
        var raw = 0L
        var writeFailed = false
        var spaceLogged = false
    }

    private class PhoneSample(val atMs: Long, val arrivedElapsed: Long)
    private class Finish(val endElapsed: Long, val endWall: Long, val endPhone: PhoneSample?, val reason: String)

    private val lock = Any()
    private val storage = TraceStorage(root, space, freeBytes, onReclaim = { r -> if (r.any) log(r.line()) }, renameDir = renameDir, deleteTree = deleteTree, pendingFile = pendingFile, writeRecord = writeRecord, readFile = readFile, receiptHeld = { queuedReceipts.get() > 0 || unpersisted != null })

    // epoch (guarded by lock)
    @Volatile private var installId = ""
    private var deletionCounter = 0L
    /**
     * LGC4: the adopted epoch is DURABLE on disk (read at construction, or written after its ordered wipe succeeded). False from the adoption of a new epoch until the wipe
     * and the epoch file are done: the persisted `diag_record` request becomes effective only then (fence, then epoch, then flag).
     */
    @Volatile private var epochDurable = false
    /** The last epoch wipe left something behind: the epoch stays unsaved and the wipe is retried at the next control. */
    @Volatile private var wipeFailed = false
    /** Epoch wipes queued on the writer and not finished yet (incremented under [lock] with the epoch adoption). No trace starts while one is pending or the last one failed. */
    private val wipesPending = AtomicInteger(0)
    @Volatile private var wipeAttempts = 0
    /**
     * LGC8-min: a received cleanup could not be PERSISTED, so nothing was deleted and no epoch was completed; [unpersisted] holds it (writer thread only) until a retry saves it.
     * Recording stays blocked ([TraceStartBlocker.CLEANUP_PERSIST_FAILED]) meanwhile.
     */
    @Volatile private var persistFailed = false
    @Volatile private var unpersisted: Received? = null
    /** Received cleanups queued on the writer and not yet saved (incremented under [lock] when the control arrives): the reclaim fence sees them before the file exists. */
    private val queuedReceipts = AtomicInteger(0)
    /** The writer has loaded the epoch (its first task); controls that arrive earlier wait in [early] and are applied, in order, right after. Declared before the init block (an inline writer runs it in the constructor). */
    private var loaded = false
    private class Early(val c: TraceControl, val elapsed: Long, val wall: Long)
    private val early = ArrayList<Early>()
    /** Discards of aborted jobs whose cleanup receipt is not durable yet (writer thread only): they run only after it is saved. */
    private val deferredDiscards = ArrayList<String>()
    private class Received(val wipe: Boolean, val installId: String, val counter: Long, val lines: List<String>)
    private var nextWipeRetryAt = 0L
    /** The epoch read from disk at construction: a replayed cleanup adopts its target epoch only while no control has changed the in-memory one since. */
    private var startEpoch: Pair<String, Long> = "" to 0L
    private fun storageSettled(): Boolean { retryWipeIfDue(); return recovered && wipesPending.get() == 0 && !wipeFailed && !persistFailed }
    // collect window
    @Volatile private var collect = false
    @Volatile private var collectUntil = Long.MAX_VALUE
    private var phone: PhoneSample? = null

    @Volatile override var recording = false
        private set
    @Volatile private var capClose = false

    // the running trace (guarded by lock)
    private var job: Job? = null
    private var cur: EventBuf? = null
    private val pool = ArrayDeque<EventBuf>()
    private var buffersMade = 0
    private var est = 0
    private var gN = 0
    private var aN = 0
    private var preRollAt = 0L
    private var preRollDone = true
    private var activeInfo: TraceInfo? = null

    /** False until the start-up recovery (queued on [io] first, so it runs before every other storage task) has finished: no trace starts before. */
    @Volatile private var recovered = false
    @Volatile private var recoveredMs = 0L
    /** Traces sealed or published since this process started (an in-memory count: reading it never touches the disk). */
    private val tracesDone = AtomicInteger(0)

    init {
        // The recorder does NO file I/O in its constructor (it is reachable from the main thread: the ANR loop of 2026-10-23). The persisted epoch (2 lines) is loaded by the FIRST writer
        // task; a control that arrives before it waits in [early] (so it can never see "no epoch" and wipe valid traces) and is applied right after the recovery; no trace starts before.
        io {
            try { storage.readEpoch().let { synchronized(lock) { installId = it.first; deletionCounter = it.second; epochDurable = it.first.isNotEmpty(); startEpoch = it } } } catch (e: Exception) { log("trace epoch not read") }
            // LGC8-min: the received cleanup is replayed FIRST, before recovery can seal anything (a killed trace in a forgotten range must be deleted, not sealed).
            try { replayPending() } catch (e: Exception) { log("trace cleanup replay failed: ${e.javaClass.simpleName}") }
            var info = RecoveryInfo(0, 0, 0)
            try {
                val t0 = System.nanoTime()
                val n = storage.recover()
                val ms = (System.nanoTime() - t0) / 1_000_000
                info = RecoveryInfo(n.found, n.sealed, ms)
                tracesDone.addAndGet(n.sealed)
                if (n.found > 0) log("trace recovery: found=${n.found} sealed=${n.sealed} ms=$ms")
            } catch (e: Exception) { log("trace recovery failed: ${e.javaClass.simpleName}") }
            recoveredMs = info.ms
            val bootInfo = RecoveryInfo(info.found, info.sealed, info.ms, epochDurable) // the state at boot: the waiting controls below are applied after it
            // The controls that waited for the load, in order, each with ITS arrival clocks. Readiness ([loaded], [recovered]) is published under the lock only when none is left, so
            // no trace can start between two waiting controls (a collect on and its collect off).
            while (true) {
                val batch = synchronized(lock) { if (early.isEmpty()) { loaded = true; recovered = true; null } else early.toList().also { early.clear() } } ?: break
                batch.forEach { applyControl(it.c, it.elapsed, it.wall) }
            }
            try { onRecovered(bootInfo) } catch (e: Exception) { log("trace boot report failed: ${e.javaClass.simpleName}") } // [recovered] is true by now
        }
    }

    /**
     * LGC8-min: a received cleanup (epoch wipe and/or forget) is still pending. The answer is the existence of `trace_cleanup_pending`, the single source of truth (a stat, no
     * state in memory). While it is true the hand-over tool refuses every trace ([exportableTraceIds] is empty).
     */
    fun cleanupPending(): Boolean = storage.cleanupPending()

    /** The sealed traces a hand-over may export: none while [cleanupPending] (coarse refusal), else every sealed trace id. */
    fun exportableTraceIds(): List<String> = storage.exportableTraceIds()

    /** Bytes under `traces/` and `imu_trace_tmp/` (tests, diagnostics). */
    val usedBytes: Long get() = storage.used.get()

    override val wantsStart: Boolean
        get() = storageSettled() && !recording && recordingAllowed(clock()) && installId.isNotEmpty() && hasRoom()

    /**
     * LGC7-min: room for the bounded first write above the floor and the sealing reserve, from memory (the writer's last measure: no I/O on the caller). When there is none, ONE retry
     * is queued on the writer (reclaim, then a fresh measure), so the next boundary sees the room that reclaim made: simple retry, no backoff scheduler.
     */
    private fun hasRoom(): Boolean {
        if (storage.hasStartRoom()) return true
        scheduleMaintenance()
        return false
    }

    private val maintenanceQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var nextMaintenanceAt = 0L

    /** Queues one space pass on the writer thread (coalesced; after a pass that found no room, the next one waits [MAINTENANCE_GAP_MS] so a full disk is not rescanned at every poll). */
    private fun scheduleMaintenance() {
        if (clock() < nextMaintenanceAt || !maintenanceQueued.compareAndSet(false, true)) return
        io {
            try {
                storage.measureBudget()
                storage.reclaim(space.startNeed)
                nextMaintenanceAt = if (storage.hasStartRoom()) 0L else clock() + MAINTENANCE_GAP_MS
            } catch (e: Exception) { log("trace space pass failed: ${e.javaClass.simpleName}") }
            finally { maintenanceQueued.set(false) }
        }
    }

    /** The laptop's `pc_trace_ctl` collect window (its own `collectUntil`). */
    private fun collectingNow(now: Long) = collect && now < collectUntil

    /**
     * THE trace gate (LGC4), the only place the two sources meet: diagnostics (the persisted flag, effective only with a durable epoch) OR an open laptop collect window.
     * Used by [wantsStart], [start], [onControl] and the append path; nothing else decides whether a trace may exist.
     */
    private fun recordingAllowed(now: Long) = diagEnabled() || collectingNow(now)

    /** The adopted epoch is durably saved (see [epochDurable]); what `DiagRecord` turns into `ENABLED` / `ARMED_NO_EPOCH`. */
    val epochSaved: Boolean get() = epochDurable

    /**
     * Why [wantsStart] is false right now, or null when a trace could start (or one is already recording: that is not a fault). A read-only derivation (no retry is queued),
     * checked in the order the guarantees were added: recovery first, then the ordered epoch wipe, then the gate, the epoch and the disk. LGC6-min puts [TraceStartBlocker.wire]
     * into `Hello.traceBlocker`; the session-side blockers (not a clean boundary) belong to LGC5.
     */
    fun startBlocker(): TraceStartBlocker? = when {
        !recovered -> TraceStartBlocker.RECOVERING
        wipesPending.get() != 0 -> TraceStartBlocker.WIPE_PENDING
        persistFailed -> TraceStartBlocker.CLEANUP_PERSIST_FAILED
        wipeFailed -> TraceStartBlocker.WIPE_FAILED
        recording -> null
        !recordingAllowed(clock()) -> TraceStartBlocker.GATE_OFF
        installId.isEmpty() -> TraceStartBlocker.NO_EPOCH
        !storage.hasStartRoom() -> TraceStartBlocker.DISK_FULL
        else -> null
    }

    /** The state line's inputs, from memory only (volatile and atomic reads: no disk, no lock, safe on any thread). */
    fun state(): TraceState {
        val now = clock()
        val c = collectingNow(now)
        return TraceState(
            recovered = recovered, recoveredMs = recoveredMs, collect = c, untilInMs = if (c && collectUntil != Long.MAX_VALUE) collectUntil - now else null,
            diag = diagEnabled(), epochSaved = epochDurable, usedBytes = storage.used.get(), softBudgetBytes = storage.softBudgetBytes, recording = recording, traces = tracesDone.get(),
        )
    }

    /** The diagnostic flag changed (the writer thread, after `DiagRecord` applied it): a running trace that nothing allows any more closes `stop` (nothing is deleted). */
    fun onDiagChanged() {
        synchronized(lock) { if (recording && !recordingAllowed(clock())) closeLocked(CloseReason.STOP) }
    }

    // ---- link: TraceControl ----

    /** The phone's `pc_trace_ctl` (link thread). Applies the epoch, then the collect window. Tombstones, forget ranges and `send` are Task 17. */
    fun onControl(c: TraceControl) {
        synchronized(lock) { if (!loaded) { early += Early(c, clock(), wallClock()); return } }
        applyControl(c, clock(), wallClock())
    }

    /** [now] and [wall] are the clocks at the control's ARRIVAL (a control that waited for the writer's load keeps its own: a long recovery must not shift ranges or extend a window). */
    private fun applyControl(c: TraceControl, now: Long, wall: Long) {
        var wipe = false
        var forgetLines: List<String> = emptyList()
        var liveId: String? = null
        synchronized(lock) {
            if (c.installId == installId && c.deletionCounter < deletionCounter) return // a stale message of this install
            phone = if (c.atMs > 0) PhoneSample(c.atMs, now) else phone
            wipe = c.installId != installId || c.deletionCounter > deletionCounter
            // The forget lines first (they need only the control): the receipt below must be the FIRST task queued, before any abort deletes anything.
            if (c.tombstones.isNotEmpty() || (c.forgetFromMs != null && c.forgetToMs != null)) {
                val offset = if (c.atMs > 0) c.atMs - wall else 0L
                val margin = if (c.atMs > 0) FORGET_MARGIN_MS else FORGET_MARGIN_NO_CLOCK_MS
                val lines = ArrayList<String>()
                c.tombstones.filter { com.rokid.context.capture.trace.TraceBounds.isTraceId(it) }.forEach { lines += "T $it" }
                if (c.forgetFromMs != null && c.forgetToMs != null && c.forgetFromMs!! - offset - margin <= c.forgetToMs!! - offset + margin) lines += "R ${c.forgetFromMs!! - offset - margin} ${c.forgetToMs!! - offset + margin}"
                forgetLines = lines
            }
            // LGC8-min receipt fence: the received cleanup is the first task on the writer and is counted at once (reclaim sees it before the file exists); every deletion below
            // (abort discards, wipe, forget, reclaim) is queued after it.
            if (wipe || forgetLines.isNotEmpty()) {
                val rcv = Received(wipe, c.installId, c.deletionCounter, forgetLines)
                queuedReceipts.incrementAndGet()
                io { try { receive(rcv) } finally { queuedReceipts.decrementAndGet() } }
            }
            if (wipe) {
                abortLocked()
                installId = c.installId; deletionCounter = c.deletionCounter
                epochDurable = false // fence first: the diagnostic request stays ineffective until the wipe ran and this epoch is on disk
            }
            // Forget (S58, P7): a running trace that overlaps (or is listed) is dropped now; the closed ones are deleted by [TraceStorage.runForget], which is retried until it succeeds.
            job?.let { j ->
                val hit = forgetLines.any { l -> l == "T ${j.id}" || (l.startsWith("R ") && l.split(' ').let { p -> j.startWall <= p[2].toLong() && wallClock() >= p[1].toLong() } ) }
                if (hit) abortLocked()
            }
            liveId = job?.id // a live (not aborted) job's tmp directory is never touched by a forget: its own tasks own it
            collect = c.collect
            collectUntil = if (c.collect && c.collectUntilMs != null && c.atMs > 0) now + (c.collectUntilMs!! - c.atMs) else Long.MAX_VALUE
            if (recording && !recordingAllowed(now)) closeLocked(CloseReason.STOP)
            // Storage work is enqueued under the SAME lock as the epoch adoption (so no start can slip between the adoption and the wipe), and the epoch to persist is captured here.
            // A trace cannot start while a wipe is pending or failed ([storageSettled]).
            if (wipe || wipeFailed) enqueueWipeLocked()
            enqueueForgetLocked(liveId)
        }
    }

    /** Queues the forget run (caller holds [lock]); [liveId] is the running job whose tmp directory is never touched. */
    private fun enqueueForgetLocked(liveId: String?) {
        val keep = liveId?.let { setOf(it) } ?: emptySet()
        io { runForgetTask(keep) }
    }

    /** Writer thread: merges a received cleanup into the unpersisted set and tries to save it. */
    private fun receive(r: Received) {
        val u = unpersisted
        unpersisted = if (u == null) r else Received(u.wipe || r.wipe, if (r.wipe) r.installId else u.installId, if (r.wipe) r.counter else u.counter, (u.lines + r.lines).distinct())
        ensurePersisted()
    }

    /** Writer thread: true when nothing is waiting to be saved. On a failure [persistFailed] blocks recording and the caller must not delete anything. */
    private fun ensurePersisted(): Boolean {
        val u = unpersisted ?: return true
        val ok = try { storage.persistCleanup(u.wipe, u.installId, u.counter, u.lines) } catch (e: Exception) { false }
        if (!ok) { persistFailed = true; log("trace cleanup record not saved: nothing deleted, retried"); return false }
        unpersisted = null; persistFailed = false
        if (deferredDiscards.isNotEmpty()) { val ids = deferredDiscards.toList(); deferredDiscards.clear(); ids.forEach { discardNow(it) } }
        return true
    }

    /**
     * Writer thread: executes the COMPLETE durable pending set (the record is the truth; the working list is refilled from it first, and a failed refill stops here), and
     * clears the forget part only when that whole set was really applied.
     */
    private fun runForgetTask(keep: Set<String>) {
        try {
            if (!ensurePersisted()) return
            val p = storage.readPending()
            if (p != null && p.lines.isNotEmpty()) storage.addForget(p.lines) // throws when not saved: nothing below runs, nothing is cleared
            if (storage.hasPendingForget()) {
                if (storage.runForget(keep)) { if (p != null) storage.finishForget() } else log("trace forget incomplete: retained, retried at the next control or start")
            }
        } catch (e: Exception) { log("trace forget failed: ${e.javaClass.simpleName}") }
    }

    /**
     * Start-up replay of a cleanup the glasses already received (writer thread, BEFORE [TraceStorage.recover]): the epoch wipe to the persisted target epoch (the target is saved
     * only after the wipe succeeded) and the forget lines, tmp directories included. Anything that fails keeps the record; recovery then leaves tmp alone until a later start succeeds.
     */
    private fun replayPending() {
        val p = storage.readPending() ?: return
        var done = true // only for the log line
        if (p.wipe) {
            val cleared = try { storage.clearEpoch() } catch (e: Exception) { false }
            val ok = cleared && try { storage.wipe() } catch (e: Exception) { false }
            var saved = false
            if (ok && p.installId != null) { try { storage.writeEpoch(p.installId, p.counter); saved = true } catch (e: Exception) { log("trace epoch not saved") } }
            val finished = ok && (p.installId == null || saved)
            synchronized(lock) {
                // adopt the target in memory unless a control already moved the epoch on; an UNKNOWN target (unreadable record) invalidates the epoch read from disk: no recording
                // under it, even with diagnostics on, until a valid control establishes and saves a new one
                if ((installId to deletionCounter) == startEpoch) {
                    if (p.installId != null) { installId = p.installId; deletionCounter = p.counter; epochDurable = saved }
                    else { installId = ""; deletionCounter = 0; epochDurable = false }
                    if (!finished) { wipeFailed = true; wipeAttempts = 1; nextWipeRetryAt = clock() + WIPE_RETRY_MS }
                }
            }
            if (finished) storage.finishWipe(p.installId ?: "", p.counter) else done = false
        }
        if (p.lines.isNotEmpty()) {
            try { storage.addForget(p.lines); if (storage.runForget()) storage.finishForget() else done = false } catch (e: Exception) { done = false }
        }
        log("trace cleanup replay: wipe=${p.wipe} lines=${p.lines.size} done=$done")
    }

    /**
     * Queues the wipe of the epoch in force (caller holds [lock]): captured here, persisted only after the wipe really succeeded. A failed wipe is retried by every
     * following control AND, with no control at all, by [retryWipeIfDue] (called from the start-eligibility checks) after a bounded backoff.
     */
    private fun enqueueWipeLocked() {
        val adoptedInstall = installId; val adoptedCounter = deletionCounter
        wipesPending.incrementAndGet()
        io {
            try {
                // Privacy order (LGC4): the old epoch leaves the disk BEFORE the fence runs, so a restart (or a failed wipe) can never read the forgotten epoch back as durable and
                // record under it. A failed clear counts as a failed wipe. The window between the in-memory adoption (onControl) and this task reaching the writer is NOT closed
                // here: it belongs to LGC8-min (persist the received cleanup and its target epoch before acting, replay before recovery).
                val persisted = ensurePersisted() // LGC8-min: no epoch clear, no delete and no epoch save before the cleanup record is durable
                val cleared = persisted && try { storage.clearEpoch() } catch (e: Exception) { false }
                val ok = cleared && try { storage.wipe() } catch (e: Exception) { log("trace wipe failed: ${e.javaClass.simpleName}"); false }
                var saved = false
                if (ok && adoptedInstall.isEmpty()) saved = true // an unknown target (unreadable record): wiped, and no epoch exists until a valid control brings one
                else if (ok) { try { storage.writeEpoch(adoptedInstall, adoptedCounter); saved = true } catch (e: Exception) { log("trace epoch not saved: ${e.javaClass.simpleName}") } }
                // An epoch that could not be written counts as a failed wipe (retried by the next control or the start checks): otherwise `armed_no_epoch` would never heal.
                if (saved && adoptedInstall.isNotEmpty()) synchronized(lock) { if (installId == adoptedInstall && deletionCounter == adoptedCounter) epochDurable = true }
                if (saved) storage.finishWipe(adoptedInstall, adoptedCounter) // LGC8-min: only now may the wipe part of the pending record go
                wipeFailed = !saved
                wipeAttempts = if (saved) 0 else (wipeAttempts + 1).coerceAtMost(WIPE_RETRY_MAX_SHIFT)
            } finally { wipesPending.decrementAndGet() }
        }
    }

    /** A failed wipe with none pending: queue it again once the backoff (2 s doubling to about 64 s) has passed, so recording never waits for a control that may not come. */
    private fun retryWipeIfDue() {
        if (!(wipeFailed || persistFailed) || wipesPending.get() != 0) return
        synchronized(lock) {
            val now = clock()
            if (!(wipeFailed || persistFailed) || wipesPending.get() != 0 || now < nextWipeRetryAt) return
            nextWipeRetryAt = now + (WIPE_RETRY_MS shl wipeAttempts)
            if (wipeFailed) enqueueWipeLocked() else enqueueForgetLocked(job?.id) // a forget-only cleanup that could not be saved: save it, then run it
        }
    }

    // ---- start / close ----

    override fun start(s: TraceStart): Boolean = synchronized(lock) {
        val now = clock()
        if (!storageSettled() || recording || !recordingAllowed(now) || installId.isEmpty() || !hasRoom()) return false
        if (s.snapshot.sampleEvery != sampleEvery) return false
        val id = newTraceId()
        if (!TraceBounds.isTraceId(id)) return false
        val b = cur ?: buffer() ?: return false // the buffer of the previous trace is reused (it was flushed at its close)
        b.clear()
        val sn = s.snapshot
        val j = Job(id, now, wallClock(), phone?.let { PhoneSample(it.atMs, it.arrivedElapsed) }, sn, installId, deletionCounter, bootId())
        job = j; cur = b; est = 0; gN = 0; aN = 0
        preRollAt = now + TraceBounds.PRE_ROLL_MS; preRollDone = false
        activeInfo = s.info
        capClose = false
        try {
            val p = s.profile
            b.add(TraceEvent.Ctx(now, sn.ctx))
            b.add(TraceEvent.Resting(now, sn.resting))
            b.add(TraceEvent.Profile(now, p.wake, p.settledMs, p.quietMs, p.walkMs, p.stillCapMs, p.analysisMs, p.tier, p.lowPower, p.heatSlow, p.quickLookMs, p.ctxCap, p.hard))
            b.add(TraceEvent.GateParams(now, s.gate.hamming, s.gate.bigChange, s.gate.ringCompareAll))
            b.add(TraceEvent.Plan(now, s.info.planGen, s.info.planHash ?: "0", s.info.planMode))
        } catch (e: IllegalArgumentException) {
            b.clear(); job = null; cur = null; activeInfo = null
            return false
        }
        est = 5 * EST_EVENT
        recording = true
        if (storage.used.get() + space.startNeed > storage.softBudgetBytes) scheduleMaintenance() // crossing the soft budget schedules a reclaim; the trace starts and keeps writing
        true
    }

    override fun close(reason: String) {
        require(reason in CloseReason.ALL) { "close reason" }
        synchronized(lock) { closeLocked(reason) }
    }

    override fun onConfig(info: TraceInfo): Boolean = synchronized(lock) {
        val a = activeInfo
        if (!recording || a == null) return false
        val reason = when {
            info.settingsHash != a.settingsHash -> CloseReason.SETTINGS
            !info.samePlan(a) -> if (info.planMode == PlanMode.SETTINGS && info.planHash == null) CloseReason.PLAN_REVERT else CloseReason.PLAN
            else -> return false
        }
        closeLocked(reason)
        true
    }

    /** Ends the running trace: the `cl` event is always recorded, the last batch is forced out and the finish is queued behind it. Caller holds [lock]. */
    private fun closeLocked(reason: String) {
        val j = job ?: return
        if (cur == null) return
        recording = false
        val t = prep()
        if (cur!!.isFull) flushLocked(force = true)
        cur!!.add(TraceEvent.Close(t, reason))
        flushLocked(force = true)
        val end = Finish(t, wallClock(), phone?.let { PhoneSample(it.atMs, it.arrivedElapsed) }, reason)
        job = null; activeInfo = null
        io { finishJob(j, end) }
    }

    /** Drops the running trace and everything buffered (a new epoch). Caller holds [lock]. */
    private fun abortLocked() {
        val j = job ?: return
        j.aborted = true
        recording = false
        job = null; activeInfo = null
        cur?.clear(); est = 0
        // The discard waits for the cleanup receipt to be DURABLE: when it could not be saved the directory stays (it is the evidence a retry still has to remove) and goes after the save.
        io { if (ensurePersisted()) discardNow(j.id) else deferredDiscards += j.id }
    }

    private fun discardNow(id: String) { try { storage.discard(id) } catch (e: Exception) { /* the wipe that follows removes it */ } }

    // ---- append path (every caller holds lock) ----

    /** The stamp: `clock()` inside the lock. Inserts the pre-roll end first when this append is the first at or after it. */
    private fun prep(): Long {
        val t = clock()
        if (!preRollDone && t >= preRollAt) { preRollDone = true; addObj(TraceEvent.PreRollEnd(preRollAt)) }
        return t
    }

    private fun addObj(e: TraceEvent) {
        var b = cur!!
        if (b.isFull) { flushLocked(force = false); b = cur!! }
        if (b.isFull) { drop("full"); return }
        b.add(e)
        est += EST_EVENT
    }

    private inline fun rec(make: (Long) -> TraceEvent) {
        if (!recording) return
        synchronized(lock) {
            if (!recording) return
            try {
                val t = prep()
                addObj(make(t))
                after(t)
            } catch (e: IllegalArgumentException) {
                drop("bounds:" + e.javaClass.simpleName) // a value outside the frozen bounds: never crash capture, mark the trace PARTIAL
            }
        }
    }

    private fun after(t: Long) {
        if (capClose) { closeLocked(CloseReason.CAP); return }
        if (!recordingAllowed(t)) { closeLocked(CloseReason.STOP); return }
        if (est >= flushBytes) flushLocked(force = false)
    }

    private fun sample(gyro: Boolean, x: Float, y: Float, z: Float, sMs: Long) {
        if (!recording) return
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || sMs < 0 || sMs > TraceBounds.MAX_MS) return
        synchronized(lock) {
            if (!recording) return
            if (sampleEvery > 1) { if (gyro) { if (gN++ % sampleEvery != 0) return } else { if (aN++ % sampleEvery != 0) return } }
            val t = prep()
            var b = cur!!
            if (b.isFull) { flushLocked(force = false); b = cur!! }
            if (b.isFull) { drop("full"); return }
            b.addSample(gyro, t, sMs, x, y, z)
            est += EST_SAMPLE
            after(t)
        }
    }

    /** Hands the buffer to the writer. At the in-flight limit the batch is dropped (PARTIAL) unless [force]. Caller holds [lock]. */
    private fun flushLocked(force: Boolean) {
        val j = job ?: return
        val b = cur ?: return
        if (b.size == 0) return
        if (!force && j.inFlight.get() >= MAX_IN_FLIGHT) { drop("inflight"); b.clear(); est = 0; return }
        val next = buffer(force)
        if (next == null) { drop("nobuffer"); b.clear(); est = 0; return }
        cur = next
        est = 0
        j.inFlight.incrementAndGet()
        io {
            try { if (!j.aborted) writeBatch(j, b.events()) } catch (e: Exception) { j.writeFailed = true; log("trace batch failed: ${e.javaClass.simpleName}") }
            finally { j.inFlight.decrementAndGet(); recycle(b) }
        }
    }

    /** A free buffer: pooled, else a new one up to [MAX_IN_FLIGHT] + 1; a forced flush (the close) may exceed that by what it needs. */
    private fun buffer(force: Boolean = false): EventBuf? =
        pool.removeLastOrNull() ?: if (force || buffersMade < MAX_IN_FLIGHT + 1) EventBuf(bufferEvents).also { buffersMade++ } else null

    /** The trace loses events (a full buffer, a writer that fell behind, a value outside the frozen bounds): it is recorded PARTIAL. Caller holds [lock]. */
    private fun drop(why: String) { job?.dropped = true; log("trace dropped events: $why") }

    private fun recycle(b: EventBuf) = synchronized(lock) { b.clear(); pool.addLast(b) }

    // ---- writer thread ----

    private fun writeBatch(j: Job, events: List<TraceEvent>) {
        if (events.isEmpty()) return
        try {
            val e = ChunkCodec.encode(j.id, j.seq, events, bounds)
            if (j.seq == 0) { storage.measureBudget(); writeStartRecord(j) } // the soft budget is re-measured at each trace start
            // LGC7-min: the next batch needs the floor, the sealing reserve and itself (the closing batch: the floor and itself). The soft budget only reclaims; PHYSICAL exhaustion alone stops the trace.
            if (!storage.ensureRoom(e.b64.length.toLong() + CHUNK_ENVELOPE, events.any { it is TraceEvent.Close })) { spaceExhausted(j); return }
            val c = TraceChunk(traceId = j.id, kind = ChunkCodec.KIND, seq = j.seq, count = 0, installId = j.installId, deletionCounter = j.counter, sha256 = e.sha256, b64 = e.b64)
            try { storage.writeChunk(j.id, j.seq, c) } catch (x: java.io.IOException) { j.writeFailed = true; log("trace chunk not written"); return }
            j.shas += e.sha256; j.events += e.eventCount; j.raw += e.rawBytes; j.seq++
            if (j.seq >= CHUNK_SOFT_LIMIT || j.raw >= RAW_SOFT_LIMIT || j.events >= EVENT_SOFT_LIMIT) capClose = true
        } catch (x: TraceFormatException) {
            // The real limit is the 90,000 base64 characters: split in two (the first half keeps the seq) and retry.
            if (events.size > 1 && x.code in SPLIT_CODES) {
                val mid = events.size / 2
                writeBatch(j, events.subList(0, mid))
                writeBatch(j, events.subList(mid, events.size))
            } else { j.writeFailed = true; log("trace chunk refused: ${x.code}") }
        }
    }

    /** No physical room for this batch even after reclaim: it is dropped (the trace is PARTIAL) and the trace closes `cap` at the next append. The recorder retries at every boundary. */
    private fun spaceExhausted(j: Job) {
        j.dropped = true
        capClose = true
        if (!j.spaceLogged) { j.spaceLogged = true; log("trace space exhausted: batch dropped, trace closes cap") }
    }

    /** The start record a restart seals a killed trace from (writer thread, once per trace, before the first chunk): the end fields are placeholders the seal replaces. */
    private fun writeStartRecord(j: Job) {
        try {
            val both = j.startPhone != null
            storage.writeStart(j.id, Manifest(
                traceId = j.id, installId = j.installId, deletionCounter = j.counter, revision = 1, parentTraceId = null, bootId = j.boot,
                startElapsedMs = j.startElapsed, endElapsedMs = j.startElapsed, startWallMs = j.startWall, endWallMs = j.startWall,
                startPhoneWallMs = if (both) j.startPhone!!.atMs else null, endPhoneWallMs = if (both) j.startPhone!!.atMs else null,
                startPhoneAgeMs = if (both) (j.startElapsed - j.startPhone!!.arrivedElapsed).coerceAtLeast(0) else null, endPhoneAgeMs = if (both) (j.startElapsed - j.startPhone!!.arrivedElapsed).coerceAtLeast(0) else null,
                phoneOffsetMs = null, offsetUncertaintyMs = null,
                chunkCount = 1, eventCount = 1, rawBytes = 1, traceSha256 = "0".repeat(64),
                recorded = TraceStatus.PARTIAL, closeReason = CloseReason.STOP, snapshot = j.snapshot,
            ))
        } catch (e: Exception) { log("trace start record not written: ${e.javaClass.simpleName}") } // the trace itself is unaffected; a kill then deletes it as before
    }

    private fun finishJob(j: Job, end: Finish) {
        try {
            if (j.aborted || j.seq == 0) { storage.discard(j.id); return }
            val both = j.startPhone != null && end.endPhone != null
            val m = Manifest(
                traceId = j.id, installId = j.installId, deletionCounter = j.counter, revision = 1, parentTraceId = null, bootId = j.boot,
                startElapsedMs = j.startElapsed, endElapsedMs = end.endElapsed, startWallMs = j.startWall, endWallMs = end.endWall,
                startPhoneWallMs = if (both) j.startPhone!!.atMs else null, endPhoneWallMs = if (both) end.endPhone!!.atMs else null,
                startPhoneAgeMs = if (both) (j.startElapsed - j.startPhone!!.arrivedElapsed).coerceAtLeast(0) else null,
                endPhoneAgeMs = if (both) (end.endElapsed - end.endPhone!!.arrivedElapsed).coerceAtLeast(0) else null,
                phoneOffsetMs = null, offsetUncertaintyMs = null,
                chunkCount = j.seq, eventCount = j.events, rawBytes = j.raw, traceSha256 = Manifest.traceSha(j.shas),
                recorded = if (j.dropped || j.writeFailed) TraceStatus.PARTIAL else TraceStatus.COMPLETE,
                closeReason = end.reason, snapshot = j.snapshot,
            )
            storage.ensureSealRoom() // no room: an IOException, the directory stays for the next recovery (nothing is discarded)
            storage.publish(j.id, m)
            tracesDone.incrementAndGet()
            storage.refreshFree()
        } catch (e: java.io.IOException) {
            // An I/O error says nothing about the trace: the directory (start record or manifest) stays in tmp and the next start's recovery finishes it.
            log("trace not published (kept for recovery): ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            log("trace not published: ${e.javaClass.simpleName}")
            try { storage.discard(j.id) } catch (x: Exception) { /* swept at the next start */ }
        }
    }

    // ---- SampleTap (sensor thread, under the classifier's lock) ----

    override fun onGyro(x: Float, y: Float, z: Float, tMs: Long, state: MotionState) = sample(true, x, y, z, tMs)
    override fun onAccel(x: Float, y: Float, z: Float, tMs: Long, gyroNewestMs: Long, state: MotionState) = sample(false, x, y, z, tMs)

    // ---- SessionTrace ----

    override fun onContext(state: ContextState) = rec { TraceEvent.Ctx(it, state) }
    override fun onResting(on: Boolean) = rec { TraceEvent.Resting(it, on) }
    override fun onProfile(p: TraceProfile) = rec { TraceEvent.Profile(it, p.wake, p.settledMs, p.quietMs, p.walkMs, p.stillCapMs, p.analysisMs, p.tier, p.lowPower, p.heatSlow, p.quickLookMs, p.ctxCap, p.hard) }
    override fun onGateParams(g: GateParams) = rec { TraceEvent.GateParams(it, g.hamming, g.bigChange, g.ringCompareAll) }
    override fun onBlocked(reason: String?) = rec { TraceEvent.Blocked(it, reason) }
    override fun onBackoff(step: Int) = rec { TraceEvent.Backoff(it, step) }
    override fun onDark(streak: Int) = rec { TraceEvent.DarkStreak(it, clampDarkStreak(streak)) }
    override fun onEpisodeOpen(cause: EpisodeCause) = rec { TraceEvent.Episode(it, true, cause) }
    override fun onEpisodeClose(cause: EpisodeCause, firstFrameAtMs: Long?, openedAtMs: Long?, outcome: EpisodeOutcome) = rec { TraceEvent.Episode(it, false, cause, firstFrameAtMs, openedAtMs, outcome) }
    override fun onScore(seq: Long, d: Long, p: Long, blur: Float, passedBlur: Boolean, motion: MotionState, dwellArmed: Boolean, force: Boolean, cap: Int?, hard: Boolean, nowMs: Long) =
        rec { TraceEvent.Score(it, seq, d, p, blur, passedBlur, motion, dwellArmed, force, cap?.takeIf { c -> c != Int.MAX_VALUE }, hard, nowMs) }
    override fun onGateKeep(seq: Long, encoded: Boolean, keptAtMs: Long?) = rec { TraceEvent.Gate(it, seq, GateOutcome.KEEP, "", encoded, if (encoded) keptAtMs else null) }
    override fun onGateSkip(seq: Long, reason: String) = rec { TraceEvent.Gate(it, seq, GateOutcome.SKIP, reason) }
    override fun onFlush(reason: String, nowMs: Long) = rec { TraceEvent.Flush(it, reason, nowMs) }

    companion object {
        /** S73-style flush point: about 64 KB of event text, well under the 90,000 character b64 bound after gzip. */
        const val FLUSH_BYTES = 64 * 1024
        const val BUFFER_EVENTS = 4096
        /** A forget range is widened by this on each side after mapping it to the glasses clock (the mapping comes from one message). */
        const val FORGET_MARGIN_MS = 5_000L
        /** With no phone clock in the control the range cannot be mapped: delete an hour either side rather than miss it. */
        const val FORGET_MARGIN_NO_CLOCK_MS = 3_600_000L
        const val MAX_IN_FLIGHT = 3
        private const val WIPE_RETRY_MS = 2_000L
        /** After a space pass that left no room, the next one waits this long. */
        private const val MAINTENANCE_GAP_MS = 5_000L
        /** A chunk file is its base64 plus the frozen envelope (`TraceFiles.MAX_FILE_CHARS - MAX_B64_CHARS`). */
        private const val CHUNK_ENVELOPE = 1_024
        private const val WIPE_RETRY_MAX_SHIFT = 5
        private const val EST_SAMPLE = 56
        private const val EST_EVENT = 72
        private const val CHUNK_SOFT_LIMIT = 4_000
        private const val RAW_SOFT_LIMIT = 250L * 1024 * 1024
        private const val EVENT_SOFT_LIMIT = 9_500_000L
        private val SPLIT_CODES = setOf("b64_too_long", "gunzip_too_big", "too_many_events")
    }
}

/** What the start-up recovery found (counts only): killed traces in tmp, how many it sealed, how long it took. */
class RecoveryInfo(val found: Int, val sealed: Int, val ms: Long, val epochSaved: Boolean = false)

/** The recorder's own state, as the state line prints it (counts and flags only). */
class TraceState(
    val recovered: Boolean, val recoveredMs: Long, val collect: Boolean, val untilInMs: Long?, val diag: Boolean, val epochSaved: Boolean,
    val usedBytes: Long, val softBudgetBytes: Long, val recording: Boolean, val traces: Int,
)

/** Why no trace can start right now ([TraceRecorder.startBlocker]); [wire] is the stable name the status line and `Hello.traceBlocker` carry. */
enum class TraceStartBlocker(val wire: String) {
    RECOVERING("recovering"), WIPE_PENDING("wipe_pending"), WIPE_FAILED("wipe_failed"), CLEANUP_PERSIST_FAILED("cleanup_persist_failed"), GATE_OFF("gate_off"), NO_EPOCH("no_epoch"), DISK_FULL("disk_full"),
}
