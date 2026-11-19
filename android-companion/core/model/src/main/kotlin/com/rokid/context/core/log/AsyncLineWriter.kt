package com.rokid.context.core.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** The few filesystem operations the writer uses, so tests can make any of them fail. */
interface LogFs {
    fun length(f: File): Long
    fun exists(f: File): Boolean
    fun delete(f: File): Boolean
    fun rename(from: File, to: File): Boolean
    fun append(f: File, bytes: ByteArray)
    /** Names in [dir] (empty when it does not exist); used by the clear to find every segment, whatever the cap was when it was written. */
    fun names(dir: File): List<String> = dir.list()?.toList() ?: emptyList()
}

object RealLogFs : LogFs {
    override fun length(f: File) = f.length()
    override fun exists(f: File) = f.exists()
    override fun delete(f: File) = f.delete()
    override fun rename(from: File, to: File) = from.renameTo(to)
    override fun append(f: File, bytes: ByteArray) { f.parentFile?.mkdirs(); f.appendBytes(bytes) }
}

/** The segment files of a log: `base` (active, newest) then `base.1` (newest retained) ... `base.N` (oldest). */
object LogSegments {
    fun name(base: File, index: Int): File = if (index == 0) base else File(base.path + "." + index)
    /** Index of [fileName] among the segments of [base] (0 = active), or null when it is not one. */
    fun indexOf(baseName: String, fileName: String): Int? =
        if (fileName == baseName) 0 else if (fileName.startsWith("$baseName.")) fileName.substring(baseName.length + 1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 } else null
}

/**
 * Bounded, fenced, non-throwing file writer for [Line]s (pure JVM), a single-owner state machine: the writer thread owns
 * all file state; callers only enqueue entries and COMMANDS (fence, clear, close) into one lock-guarded structure and never
 * wait for IO. Commands run before queued entries, and every fence/clear discards the queued entries and the overflow count
 * in the same locked transition that enqueues the command, so nothing queued before it can be written after it.
 *
 * - [append]: non-blocking; on overflow drops the OLDEST entry and counts it (one `log overflow dropped=N` line later).
 * - [fence]/[clear]: return a future completed by the writer once any in-flight write has finished and the barrier is passed
 *   (true), or false if the writer closed first. [clear] also deletes both rotations; a failed deletion is retained and retried
 *   with bounded backoff, and entries wait behind it.
 * - Every filesystem result is checked; a failure counts [ioFailures], drops that record, and starts a backoff whose deadline
 *   is enforced BETWEEN attempts (entries keep queueing within [capacity] with overflow accounting). A failed rotation stops
 *   appends (no growth past the limit). Each segment stays within [segmentBytes] and there are at most maxBytes / segmentBytes of them
 *   (default: two, the old behaviour), so disk use is at most [maxBytes]. A rotation shifts `log.k` to `log.k+1` (highest first, the
 *   oldest dropped), so `log.1` is always the newest retained and the highest number the oldest; a trip build keeps about 300 MB.
 * - [close]: drain (write what is queued, unless a failure is pending) or discard; returns a termination future. The closed
 *   check is made under the queue lock, so no append can enqueue after the worker exits.
 * [beforeWrite] (record in flight), [afterWork] (a work item finished) and [onBackoffWait] (worker re-checked the clock during a
 * backoff and did not attempt) are test seams called on the writer thread; [clockNanos] and [fs] are test seams too.
 */
class AsyncLineWriter(
    private val file: File,
    private val maxBytes: Long = 3_000_000L,
    private val capacity: Int = 2048,
    private val backoffMillis: Long = 500,
    private val fs: LogFs = RealLogFs,
    private val clockNanos: () -> Long = System::nanoTime,
    private val beforeWrite: () -> Unit = {},
    private val afterWork: () -> Unit = {},
    private val onBackoffWait: () -> Unit = {},
    private val segmentBytes: Long = maxBytes / 2,
) {
    /** One command with ONE shared acknowledgement, however many callers coalesced into it (retained state is O(1) per command). */
    private class Cmd(var clear: Boolean, val ack: CompletableFuture<Boolean> = CompletableFuture())

    private sealed class Work {
        class Fence(val cmd: Cmd) : Work()
        class Clear(val cmd: Cmd) : Work()
        class Write(val line: Line) : Work()
        class Overflow(val n: Long) : Work()
        class Terminate(val acks: List<CompletableFuture<Boolean>>) : Work()
    }

    init { require(segmentBytes >= MAX_RECORD_BYTES) { "a segment must allow one maximum record (${MAX_RECORD_BYTES} bytes)" } }

    /** Files kept in all, the active one included (at least two). */
    private val segments = maxOf(2L, maxBytes / segmentBytes).toInt()
    private val lock = Object()
    private val entries = ArrayDeque<Line>()      // guarded by lock, size <= capacity
    private val cmds = ArrayDeque<Cmd>()          // guarded by lock, at most MAX_CMDS
    private var dropped = 0L                      // guarded by lock
    private var busy = false                      // guarded by lock
    private var closed = false                    // guarded by lock
    private var drain = false                     // guarded by lock
    @Volatile private var ioFailureCount = 0
    private var rotationPending = false           // writer thread: a needed rotation has not completed yet
    private var consecutive = 0                   // writer thread; read under lock only by the writer
    @Volatile private var nextAttempt = 0L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)   // writer thread only
    private val terminated = CompletableFuture<Unit>()

    init { Thread({ runLoop() }, "pc-log-writer").apply { isDaemon = true; start() } }

    fun append(line: Line) {
        synchronized(lock) {
            if (closed) return
            if (entries.size >= capacity) { entries.pollFirst(); dropped++ }
            entries.addLast(line)
            lock.notifyAll()
        }
    }

    fun fence(): CompletableFuture<Boolean> = command(clear = false)
    fun clear(): CompletableFuture<Boolean> = command(clear = true)

    private fun command(clear: Boolean): CompletableFuture<Boolean> {
        synchronized(lock) {
            if (closed) return CompletableFuture.completedFuture(false)
            entries.clear(); dropped = 0
            val tail = cmds.peekLast()
            val cmd = if (tail != null && (tail.clear == clear || cmds.size >= MAX_CMDS)) tail.also { it.clear = it.clear || clear }
                      else Cmd(clear).also { cmds.addLast(it) }
            lock.notifyAll()
            return cmd.ack      // callers that coalesce share the same future (a fence upgraded to a clear completes when the clear is done)
        }
    }

    /** Stops the writer: [drain] writes what is queued first (unless a failure is pending), otherwise discards it. */
    fun close(drain: Boolean = false): CompletableFuture<Unit> {
        synchronized(lock) {
            if (!closed) { closed = true; this.drain = drain; lock.notifyAll() }
        }
        return terminated
    }

    fun queueSize(): Int = synchronized(lock) { entries.size }
    fun dropped(): Long = synchronized(lock) { dropped }
    fun ioFailures(): Int = ioFailureCount
    internal fun retainedAcksForTest(): Int = synchronized(lock) { cmds.size }   // one shared ack per retained command

    /** Waits until nothing is queued or in flight and no command is pending (tests, and flush before a pull). */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < end) {
            synchronized(lock) { if (entries.isEmpty() && cmds.isEmpty() && !busy && dropped == 0L) return true }
            Thread.sleep(5)
        }
        return false
    }

    private fun due() = clockNanos() >= nextAttempt

    private fun nextWork(): Work {
        synchronized(lock) {
        while (true) {
            if (closed && (!drain || consecutive > 0)) {
                val acks = cmds.map { it.ack }
                cmds.clear(); entries.clear(); dropped = 0
                return Work.Terminate(acks)
            }
            val c = cmds.peekFirst()
            if (c != null) {
                if (!c.clear) { cmds.pollFirst(); busy = true; return Work.Fence(c) }
                if (due()) { busy = true; return Work.Clear(c) }       // stays at the head until it succeeds
            } else if (entries.isNotEmpty() || dropped > 0) {
                if (due()) {
                    busy = true
                    return if (entries.isNotEmpty()) Work.Write(entries.pollFirst()) else Work.Overflow(dropped.also { dropped = 0 })
                }
            } else {
                if (closed) return Work.Terminate(emptyList())
                lock.wait(1000); continue
            }
            // waiting out a backoff: re-check the clock in short slices (appends keep queueing, bounded)
            try { onBackoffWait() } catch (_: Throwable) { }
            val remainMs = ((nextAttempt - clockNanos()) / 1_000_000).coerceIn(1, 20)
            lock.wait(remainMs)
        }
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val work = nextWork()
                try {
                    when (work) {
                        is Work.Terminate -> { work.acks.forEach { it.complete(false) }; terminated.complete(Unit); return }
                        is Work.Fence -> work.cmd.ack.complete(true)
                        is Work.Clear -> if (doClear()) {
                            synchronized(lock) { if (cmds.peekFirst() === work.cmd) cmds.pollFirst() }
                            work.cmd.ack.complete(true)
                        }
                        is Work.Write -> { hook(); write(format(work.line)) }
                        is Work.Overflow -> { hook(); write(format(Line(Level.W, "PcLog", "log overflow dropped=${work.n}", System.currentTimeMillis()))) }
                    }
                } catch (_: Throwable) { fail() }
                finally { synchronized(lock) { busy = false }; try { afterWork() } catch (_: Throwable) { } }
            }
        } finally { terminated.complete(Unit) }
    }

    private fun hook() { try { beforeWrite() } catch (_: Throwable) { } }

    private fun doClear(): Boolean {
        var ok = true
        val dir = file.parentFile
        val names = try { if (dir == null) emptyList() else fs.names(dir).filter { LogSegments.indexOf(file.name, it) != null } } catch (_: Throwable) { ok = false; emptyList() }
        val all = (names.map { File(dir, it) } + (0 until segments).map { LogSegments.name(file, it) }).distinct()      // listed ones, plus the known names when the listing fails
        for (f in all) {
            try { if (fs.exists(f) && !fs.delete(f) && fs.exists(f)) ok = false } catch (_: Throwable) { ok = false }
        }
        if (ok) succeed() else fail()
        return ok
    }

    private fun format(l: Line) =
        "${fmt.format(Date(l.wallMillis))} ${l.level} ${l.tag} ${l.msg.replace('\n', ' ')}\n".toByteArray(Charsets.UTF_8)

    private fun write(bytes: ByteArray) {
        try {
            val len = fs.length(file)
            if (len == 0L) rotationPending = false          // nothing to rotate (new file, or cleared meanwhile)
            if (len > 0 && (rotationPending || len + bytes.size > segmentBytes)) {
                rotationPending = true          // retained until it succeeds: nothing is appended around a failed rotation
                if (!rotate()) return fail()
                rotationPending = false
            }
            fs.append(file, bytes)
            succeed()
        } catch (_: Throwable) { fail() }
    }

    /** Drops the oldest segment, shifts every other one up and moves the active file to `.1`. Idempotent, so a retry after a partial failure just continues. */
    private fun rotate(): Boolean {
        val last = segments - 1
        val oldest = LogSegments.name(file, last)
        if (fs.exists(oldest) && !fs.delete(oldest)) return false
        for (k in last - 1 downTo 0) {
            val src = LogSegments.name(file, k)
            if (!fs.exists(src)) continue
            val dst = LogSegments.name(file, k + 1)
            if (fs.exists(dst) && !fs.delete(dst)) return false
            if (!fs.rename(src, dst)) return false
        }
        return true
    }

    private fun succeed() { consecutive = 0; nextAttempt = 0L }

    private fun fail() {
        ioFailureCount++
        consecutive++
        nextAttempt = clockNanos() + (backoffMillis shl minOf(consecutive - 1, 4)) * 1_000_000
    }

    companion object {
        private const val MAX_CMDS = 16
        /** Upper bound of one formatted record in bytes (timestamp 18 + level + tag 64 + msg 1024 + separators), rounded up. */
        const val MAX_RECORD_BYTES = 1_200
    }
}
