package com.rokid.context.core.log

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, fenced, non-throwing file writer for [Line]s (pure JVM). [append] is a non-blocking offer on a bounded queue
 * (drops the OLDEST on overflow and counts it); one daemon thread formats, rotates (two files, at most [maxBytes] on disk,
 * the DiagFile rule) and writes. No lock shared with callers is held across IO; every IO failure is caught and counted.
 * [fence] discards queued and not-yet-written lines; [clear] also deletes both files on the writer thread.
 * [beforeWrite] is a test seam called by the writer thread before each queued line.
 */
class AsyncLineWriter(
    private val file: File,
    private val maxBytes: Long = 3_000_000L,
    private val capacity: Int = 2048,
    private val backoffMillis: Long = 500,
    private val beforeWrite: () -> Unit = {},
) {
    private class Entry(val gen: Long, val line: Line)

    private val older = File(file.path + ".1")
    private val lock = Object()
    private val queue = ArrayDeque<Entry>()
    private var busy = false
    @Volatile private var gen = 0L
    @Volatile private var closed = false
    private val clearPending = AtomicBoolean(false)
    private val dropped = AtomicLong(0)
    private val ioFailures = AtomicInteger(0)
    private var consecutiveFailures = 0
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)   // writer thread only

    private val thread = Thread({ runLoop() }, "pc-log-writer").apply { isDaemon = true; start() }

    fun append(line: Line) {
        if (closed) return
        synchronized(lock) {
            if (queue.size >= capacity) { queue.pollFirst(); dropped.incrementAndGet() }
            queue.addLast(Entry(gen, line))
            lock.notifyAll()
        }
    }

    /** Discards queued lines and any line the writer has not yet written. */
    fun fence() { synchronized(lock) { gen++; queue.clear(); lock.notifyAll() } }

    /** [fence] plus deleting both rotations on the writer thread. */
    fun clear() {
        clearPending.set(true)      // before the generation bump: a line with the new generation implies this flag is visible
        fence()
    }

    fun queueSize(): Int = synchronized(lock) { queue.size }
    fun dropped(): Long = dropped.get()
    fun ioFailures(): Int = ioFailures.get()

    /** Waits until nothing is queued or being written (tests, and flush before a pull). */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < end) {
            synchronized(lock) { if (queue.isEmpty() && !busy && !clearPending.get() && dropped.get() == 0L) return true }
            Thread.sleep(5)
        }
        return false
    }

    fun close() { closed = true; synchronized(lock) { lock.notifyAll() } }

    private fun runLoop() {
        while (!closed) {
            val batch: List<Entry>
            synchronized(lock) {
                while (queue.isEmpty() && dropped.get() == 0L && !clearPending.get() && !closed) lock.wait(1000)
                if (closed) return
                batch = queue.toList(); queue.clear(); busy = true
            }
            try { process(batch) } catch (_: Throwable) { count() }
            synchronized(lock) { busy = false }
            if (consecutiveFailures >= 3) try { Thread.sleep(backoffMillis) } catch (_: InterruptedException) { return }
        }
    }

    private fun process(batch: List<Entry>) {
        for (e in batch) {
            try { beforeWrite() } catch (_: Throwable) { }
            doClearIfPending()
            if (e.gen != gen) continue
            write(format(e.line))
        }
        doClearIfPending()
        val n = dropped.getAndSet(0)
        if (n > 0) write(format(Line(Level.W, "PcLog", "log overflow dropped=$n", System.currentTimeMillis())))
    }

    private fun doClearIfPending() {
        if (clearPending.getAndSet(false)) try { file.delete(); older.delete() } catch (_: Throwable) { count() }
    }

    private fun format(l: Line) =
        "${fmt.format(Date(l.wallMillis))} ${l.level} ${l.tag} ${l.msg.replace('\n', ' ')}\n"

    private fun write(text: String) {
        try {
            if (file.length() >= maxBytes / 2) { older.delete(); file.renameTo(older) }
            file.parentFile?.mkdirs()
            file.appendText(text)
            consecutiveFailures = 0
        } catch (_: Throwable) { count() }
    }

    private fun count() { ioFailures.incrementAndGet(); consecutiveFailures++ }
}
