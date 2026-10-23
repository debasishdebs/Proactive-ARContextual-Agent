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
}

object RealLogFs : LogFs {
    override fun length(f: File) = f.length()
    override fun exists(f: File) = f.exists()
    override fun delete(f: File) = f.delete()
    override fun rename(from: File, to: File) = from.renameTo(to)
    override fun append(f: File, bytes: ByteArray) { f.parentFile?.mkdirs(); f.appendBytes(bytes) }
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
 *   appends (no growth past the limit). Each file stays within maxBytes / 2, so disk use is at most [maxBytes].
 * - [close]: drain (write what is queued, unless a failure is pending) or discard; returns a termination future. The closed
 *   check is made under the queue lock, so no append can enqueue after the worker exits.
 * [beforeWrite] is a test seam called on the writer thread with a record in flight; [clockNanos] and [fs] are test seams too.
 */
class AsyncLineWriter(
    private val file: File,
    private val maxBytes: Long = 3_000_000L,
    private val capacity: Int = 2048,
    private val backoffMillis: Long = 500,
    private val fs: LogFs = RealLogFs,
    private val clockNanos: () -> Long = System::nanoTime,
    private val beforeWrite: () -> Unit = {},
) {
    private class Cmd(var clear: Boolean, val acks: MutableList<CompletableFuture<Boolean>>)

    private sealed class Work {
        class Fence(val cmd: Cmd) : Work()
        class Clear(val cmd: Cmd) : Work()
        class Write(val line: Line) : Work()
        class Overflow(val n: Long) : Work()
        class Terminate(val acks: List<CompletableFuture<Boolean>>) : Work()
    }

    private val older = File(file.path + ".1")
    private val lock = Object()
    private val entries = ArrayDeque<Line>()      // guarded by lock, size <= capacity
    private val cmds = ArrayDeque<Cmd>()          // guarded by lock, at most MAX_CMDS
    private var dropped = 0L                      // guarded by lock
    private var busy = false                      // guarded by lock
    private var closed = false                    // guarded by lock
    private var drain = false                     // guarded by lock
    @Volatile private var ioFailureCount = 0
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
        val f = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (closed) { f.complete(false); return f }
            entries.clear(); dropped = 0
            val tail = cmds.peekLast()
            if (tail != null && (tail.clear == clear || cmds.size >= MAX_CMDS)) { tail.clear = tail.clear || clear; tail.acks += f }
            else cmds.addLast(Cmd(clear, mutableListOf(f)))
            lock.notifyAll()
        }
        return f
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
                val acks = cmds.flatMap { it.acks }
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
                        is Work.Fence -> work.cmd.acks.forEach { it.complete(true) }
                        is Work.Clear -> if (doClear()) {
                            synchronized(lock) { if (cmds.peekFirst() === work.cmd) cmds.pollFirst() }
                            work.cmd.acks.forEach { it.complete(true) }
                        }
                        is Work.Write -> { hook(); write(format(work.line)) }
                        is Work.Overflow -> { hook(); write(format(Line(Level.W, "PcLog", "log overflow dropped=${work.n}", System.currentTimeMillis()))) }
                    }
                } catch (_: Throwable) { fail() }
                finally { synchronized(lock) { busy = false } }
            }
        } finally { terminated.complete(Unit) }
    }

    private fun hook() { try { beforeWrite() } catch (_: Throwable) { } }

    private fun doClear(): Boolean {
        var ok = true
        for (f in listOf(file, older)) {
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
            if (len > 0 && len + bytes.size > maxBytes / 2) {
                if (fs.exists(older) && !fs.delete(older)) return fail()
                if (!fs.rename(file, older)) return fail()
            }
            fs.append(file, bytes)
            succeed()
        } catch (_: Throwable) { fail() }
    }

    private fun succeed() { consecutive = 0; nextAttempt = 0L }

    private fun fail() {
        ioFailureCount++
        consecutive++
        nextAttempt = clockNanos() + (backoffMillis shl minOf(consecutive - 1, 4)) * 1_000_000
    }

    private companion object { const val MAX_CMDS = 16 }
}
