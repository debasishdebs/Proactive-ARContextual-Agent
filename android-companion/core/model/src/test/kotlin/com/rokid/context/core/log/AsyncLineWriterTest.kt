package com.rokid.context.core.log

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [LogFs] over the real one whose operations can be made to fail, with attempt counters. */
private class FlakyFs : LogFs {
    val failDelete = AtomicBoolean(false); val failRename = AtomicBoolean(false); val failAppend = AtomicBoolean(false)
    val appends = AtomicInteger(0); val deletes = AtomicInteger(0)
    override fun length(f: File) = RealLogFs.length(f)
    override fun exists(f: File) = RealLogFs.exists(f)
    override fun delete(f: File): Boolean { deletes.incrementAndGet(); return if (failDelete.get()) false else RealLogFs.delete(f) }
    override fun rename(from: File, to: File) = if (failRename.get()) false else RealLogFs.rename(from, to)
    override fun append(f: File, bytes: ByteArray) { appends.incrementAndGet(); if (failAppend.get()) throw IOException("disk"); RealLogFs.append(f, bytes) }
}

private class Gate {
    val entered = CountDownLatch(1); val release = CountDownLatch(1); val armed = AtomicBoolean(true)
    val hook: () -> Unit = { if (armed.getAndSet(false)) { entered.countDown(); release.await(30, TimeUnit.SECONDS) } }
}

class AsyncLineWriterTest {
    private val dir = kotlin.io.path.createTempDirectory("alw").toFile()
    private val pca = File(dir, "pca.log")
    private val open = mutableListOf<AsyncLineWriter>()
    private fun line(i: Any, level: Level = Level.I) = Line(level, "T", "line $i", 0L)
    private fun writer(
        maxBytes: Long = 3_000_000L, capacity: Int = 2048, backoff: Long = 1, fs: LogFs = RealLogFs,
        clock: () -> Long = System::nanoTime, hook: () -> Unit = {},
    ) = AsyncLineWriter(pca, maxBytes, capacity, backoff, fs, clock, hook).also { open += it }

    @BeforeTest fun setUp() { PcLog.resetForTest() }
    @AfterTest fun tearDown() { PcLog.resetForTest(); open.forEach { it.close() } }

    @Test fun writesFormattedLinesOffTheCallerThread() {
        val w = writer()
        w.append(line(1)); w.append(Line(Level.W, "PcX", "two\nlines", 0L))
        assertTrue(w.awaitIdle(5_000))
        val lines = pca.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("I T line 1"), lines[0]); assertTrue(lines[1].endsWith("W PcX two lines"), lines[1])
    }

    @Test fun stalledWriterDoesNotBlockProductionLoggingThroughPcLogAndItsPort() {
        val gate = Gate()
        val w = writer(capacity = 100, hook = gate.hook)
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { if (route.file) w.append(line) } })
        val recorderLock = Any()
        val done = CountDownLatch(1)
        val port = PcLog.port("canvas")
        try {
            PcLog.e("T", "first")
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "writer never entered its stall")
            val t = Thread {
                synchronized(recorderLock) {     // a fake recorder lock held while logging: the logger must not wait on IO
                    repeat(50_000) { PcLog.e("T", "direct $it"); port.log(Level.E, "T", "port $it") }
                }
                done.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue(done.await(60, TimeUnit.SECONDS), "deadlock guard: callers blocked behind a stalled writer")
            assertTrue(w.queueSize() <= 100)
            assertTrue(w.dropped() >= 100_000 - 100 - 1, "dropped=${w.dropped()}")
        } finally { gate.release.countDown() }
        assertTrue(w.awaitIdle(10_000))
        val text = pca.readText()
        assertTrue(text.contains("log overflow dropped=")); assertTrue(text.contains("port 49999"))
    }

    @Test fun fenceAfterTheWriterTookTheEntryStillOrdersTheAckAfterThatWrite() {
        val gate = Gate()
        val w = writer(hook = gate.hook)
        w.append(line("inflight")); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))   // taken, not yet written
        repeat(5) { w.append(line("queued$it")) }
        val ack = w.fence()
        assertFalse(ack.isDone, "the ack must wait for the in-flight write")
        assertEquals(0, w.queueSize(), "queued entries are discarded synchronously by fence")
        gate.release.countDown()
        assertTrue(ack.get(10, TimeUnit.SECONDS))
        val atAck = pca.readText()
        assertTrue(atAck.contains("line inflight") && !atAck.contains("queued"), atAck)   // nothing pre-fence is written after the ack
        w.append(line("post")); assertTrue(w.awaitIdle(5_000))
        assertTrue(pca.readText().contains("line post")); assertFalse(pca.readText().contains("queued"))
    }

    @Test fun clearVersusAppendKeepsOnlyPostClearLines() {
        val gate = Gate()
        val w = writer(hook = gate.hook)
        w.append(line("old")); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        w.append(line("oldqueued"))
        val ack = w.clear()
        w.append(line("post"))
        gate.release.countDown()
        assertTrue(ack.get(10, TimeUnit.SECONDS)); assertTrue(w.awaitIdle(5_000))
        val text = pca.readText()
        assertTrue(text.contains("line post") && !text.contains("old"), text)
    }

    @Test fun clearDiscardsOverflowAccountingSoNoOldMarkerRecreatesTheFile() {
        val gate = Gate()
        val w = writer(capacity = 3, hook = gate.hook)
        w.append(line("a")); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        repeat(50) { w.append(line(it)) }
        assertTrue(w.dropped() > 0)
        val ack = w.clear()
        assertEquals(0L, w.dropped())
        gate.release.countDown()
        assertTrue(ack.get(10, TimeUnit.SECONDS)); assertTrue(w.awaitIdle(5_000))
        assertFalse(pca.exists(), "no old-generation line or overflow marker may recreate the file")
    }

    @Test fun failedDeleteIsCountedRetainedAndRetried() {
        val fs = FlakyFs()
        val w = writer(fs = fs)
        w.append(line("secret")); assertTrue(w.awaitIdle(5_000)); assertTrue(pca.exists())
        fs.failDelete.set(true)
        val ack = w.clear()
        Thread.sleep(150)
        assertFalse(ack.isDone, "a failed deletion must not be acknowledged")
        assertTrue(w.ioFailures() >= 1); assertTrue(pca.exists())
        w.append(line("blockedBehindClear")); Thread.sleep(50)
        assertFalse(pca.readText().contains("blockedBehindClear"), "entries wait behind a pending clear")
        fs.failDelete.set(false)
        assertTrue(ack.get(10, TimeUnit.SECONDS)); assertTrue(w.awaitIdle(5_000))
        assertTrue(pca.readText().contains("blockedBehindClear")); assertFalse(pca.readText().contains("secret"))
    }

    @Test fun rotationFailureStopsAppendsUntilRotationWorks() {
        val fs = FlakyFs()
        val w = writer(maxBytes = 400, fs = fs)
        repeat(4) { w.append(Line(Level.I, "T", "fill $it ".padEnd(60, 'x'), 0L)) }
        assertTrue(w.awaitIdle(5_000))
        fs.failRename.set(true)
        val before = pca.length()
        repeat(30) { w.append(line("blocked$it")) }
        assertTrue(w.awaitIdle(5_000))
        assertEquals(before, pca.length(), "no growth while a required rotation fails")
        assertTrue(w.ioFailures() >= 1)
        fs.failRename.set(false)
        w.append(line("again")); assertTrue(w.awaitIdle(5_000))
        assertTrue(File(dir, "pca.log.1").exists()); assertTrue(pca.readText().contains("line again"))
    }

    @Test fun backoffDeadlineIsEnforcedBetweenAttemptsAndRecovers() {
        val fs = FlakyFs(); fs.failAppend.set(true)
        val now = AtomicLong(1_000_000_000L)
        val gate = Gate()
        val w = writer(backoff = 10_000, fs = fs, clock = { now.get() }, hook = gate.hook)
        w.append(line("head")); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        repeat(500) { w.append(line(it)) }                  // a full queue is waiting behind the failing write
        gate.release.countDown()
        Thread.sleep(300)                                    // many 20 ms slices pass; the fake clock does not
        assertEquals(1, fs.appends.get(), "exactly one attempt until the backoff deadline passes")
        assertEquals(500, w.queueSize())                     // still bounded, still queued
        now.addAndGet(11_000L * 1_000_000)                   // past the deadline
        val t0 = System.nanoTime()
        while (fs.appends.get() < 2 && System.nanoTime() - t0 < 5_000_000_000L) Thread.sleep(5)
        assertEquals(2, fs.appends.get(), "one more attempt after the deadline, then it backs off again")
        Thread.sleep(100); assertEquals(2, fs.appends.get())
        fs.failAppend.set(false)
        now.addAndGet(1_000_000L * 1_000_000)
        assertTrue(w.awaitIdle(10_000)); assertTrue(pca.readText().contains("line 499"))
    }

    @Test fun rotationKeepsTwoFilesWithinTheByteBudgetEvenForMultiByteText() {
        val w = writer(maxBytes = 4_000)
        repeat(300) { w.append(Line(Level.I, "PcCapture", "€€€ line $it ".padEnd(80, 'é'), 0L)) }
        assertTrue(w.awaitIdle(5_000))
        val b = File(dir, "pca.log.1")
        assertTrue(b.exists())
        assertTrue(pca.length() <= 2_000 && b.length() <= 2_000, "a=${pca.length()} b=${b.length()}")
        assertEquals(setOf("pca.log", "pca.log.1"), dir.listFiles()!!.map { it.name }.toSet())
        assertTrue(pca.readLines().last().contains("line 299"))
    }

    @Test fun clearDeletesBothRotationsOnTheWriterThread() {
        val w = writer(maxBytes = 400)
        repeat(50) { w.append(line(it)) }
        assertTrue(w.awaitIdle(5_000)); assertTrue(File(dir, "pca.log.1").exists())
        assertTrue(w.clear().get(5, TimeUnit.SECONDS))
        assertFalse(pca.exists()); assertFalse(File(dir, "pca.log.1").exists())
        w.append(line("again")); assertTrue(w.awaitIdle(5_000))
        assertTrue(pca.readText().contains("line again"))
    }

    @Test fun writerIoFailureIsolatedAndNeverThrows() {
        val blocker = File(dir, "blocker").apply { writeText("a file, not a directory") }
        val w = AsyncLineWriter(File(blocker, "pca.log"), backoffMillis = 1).also { open += it }
        repeat(20) { w.append(line(it)) }
        assertTrue(w.awaitIdle(5_000)); assertTrue(w.ioFailures() >= 1)
        w.append(line("after")); assertTrue(w.awaitIdle(5_000))
    }

    @Test fun closeDrainWritesWhatIsQueuedAndTerminates() {
        val gate = Gate()
        val w = writer(hook = gate.hook)
        w.append(line(0)); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        repeat(9) { w.append(line(it + 1)) }
        val term = w.close(drain = true)
        w.append(line("late"))                       // after close: ignored
        gate.release.countDown()
        term.get(10, TimeUnit.SECONDS)
        val text = pca.readText()
        assertEquals(10, text.lines().count { it.contains(" line ") }); assertFalse(text.contains("late"))
    }

    @Test fun closeDiscardDropsQueuedAndFailsPendingAcks() {
        val gate = Gate()
        val w = writer(hook = gate.hook)
        w.append(line(0)); assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
        repeat(9) { w.append(line(it + 1)) }
        val ack = w.clear()
        val term = w.close(drain = false)
        gate.release.countDown()
        term.get(10, TimeUnit.SECONDS)
        assertFalse(ack.get(10, TimeUnit.SECONDS), "a clear that never ran is not acknowledged as done")
        assertFalse(w.fence().get(5, TimeUnit.SECONDS), "commands after close are refused")
    }

    @Test fun appendCloseRaceNeverEnqueuesAfterWorkerExit() {
        repeat(20) {
            val d = kotlin.io.path.createTempDirectory("race").toFile()
            val w = AsyncLineWriter(File(d, "pca.log"), backoffMillis = 1)
            val go = CountDownLatch(1)
            val ts = (1..4).map { Thread { go.await(); repeat(2_000) { w.append(line(it)) } }.apply { start() } }
            go.countDown(); w.close(drain = false).get(10, TimeUnit.SECONDS)
            ts.forEach { it.join(10_000) }
            assertEquals(0, w.queueSize(), "an append enqueued after the worker exited")
        }
    }
}
