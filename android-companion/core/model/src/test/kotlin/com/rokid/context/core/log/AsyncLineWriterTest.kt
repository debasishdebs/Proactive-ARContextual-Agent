package com.rokid.context.core.log

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncLineWriterTest {
    private val dir = kotlin.io.path.createTempDirectory("alw").toFile()
    private fun line(i: Any, level: Level = Level.I) = Line(level, "T", "line $i", 0L)

    @Test fun writesFormattedLinesOffTheCallerThread() {
        val w = AsyncLineWriter(File(dir, "pca.log"))
        w.append(line(1)); w.append(Line(Level.W, "PcX", "two\nlines", 0L))
        assertTrue(w.awaitIdle(5_000))
        val lines = File(dir, "pca.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("I T line 1"), lines[0])
        assertTrue(lines[1].endsWith("W PcX two lines"), lines[1])
        w.close()
    }

    @Test fun stalledWriterDoesNotBlockCallers() {
        val latch = CountDownLatch(1)
        val w = AsyncLineWriter(File(dir, "pca.log"), capacity = 100, beforeWrite = { latch.await(30, TimeUnit.SECONDS) })
        val t0 = System.nanoTime()
        val t = Thread { repeat(100_000) { w.append(line(it)) } }.apply { isDaemon = true; start() }
        t.join(5_000)
        val blocked = t.isAlive
        val queued = w.queueSize(); val dropped = w.dropped()
        latch.countDown()   // never leave the writer stalled, even when an assertion below fails
        assertFalse(blocked, "callers blocked behind a stalled writer")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 5_000, "100k appends took $ms ms with the writer stalled")
        assertTrue(queued <= 100)
        assertTrue(dropped >= 99_000 - 1, "dropped=$dropped")
        assertTrue(w.awaitIdle(10_000))
        val text = File(dir, "pca.log").readText()
        assertTrue(text.contains("log overflow dropped="), "overflow line emitted once the writer catches up")
        assertTrue(text.contains("line 99999"), "newest lines kept (drop oldest)")
        w.close()
    }

    @Test fun writerIoFailureIsolated() {
        val blocker = File(dir, "blocker").apply { writeText("a file, not a directory") }
        val w = AsyncLineWriter(File(blocker, "pca.log"), backoffMillis = 1)
        repeat(20) { w.append(line(it)) }   // never throws
        assertTrue(w.awaitIdle(5_000))
        assertTrue(w.ioFailures() >= 1)
        // writer survives: a healthy path elsewhere still works with a new writer, and this one keeps accepting
        w.append(line("after")); assertTrue(w.awaitIdle(5_000))
        w.close()
    }

    @Test fun fenceDropsQueuedLines() {
        val latch = CountDownLatch(1)
        val w = AsyncLineWriter(File(dir, "pca.log"), beforeWrite = { latch.await(30, TimeUnit.SECONDS) })
        repeat(10) { w.append(line("old$it")) }
        w.fence()
        w.append(line("new"))
        latch.countDown()
        assertTrue(w.awaitIdle(5_000))
        val text = File(dir, "pca.log").readText()
        assertFalse(text.contains("old"), text)
        assertTrue(text.contains("line new"))
        w.close()
    }

    @Test fun clearDeletesBothRotationsOnTheWriterThread() {
        val f = File(dir, "pca.log")
        val w = AsyncLineWriter(f, maxBytes = 400)
        repeat(50) { w.append(line(it)) }
        assertTrue(w.awaitIdle(5_000)); assertTrue(File(dir, "pca.log.1").exists())
        w.clear(); assertTrue(w.awaitIdle(5_000))
        assertFalse(f.exists()); assertFalse(File(dir, "pca.log.1").exists())
        w.append(line("again")); assertTrue(w.awaitIdle(5_000))
        assertTrue(f.readText().contains("line again"))
        w.close()
    }

    @Test fun rotationKeepsTwoFiles() {
        val w = AsyncLineWriter(File(dir, "pca.log"), maxBytes = 2_000)
        repeat(200) { w.append(Line(Level.I, "PcCapture", "line $it ".padEnd(60, 'x'), 0L)) }
        assertTrue(w.awaitIdle(5_000))
        val a = File(dir, "pca.log"); val b = File(dir, "pca.log.1")
        assertTrue(b.exists())
        assertTrue(a.length() + b.length() <= 2_000 + 100)
        assertEquals(setOf("pca.log", "pca.log.1"), dir.listFiles()!!.map { it.name }.toSet())
        assertTrue(a.readLines().last().contains("line 199"))
        w.close()
    }
}
