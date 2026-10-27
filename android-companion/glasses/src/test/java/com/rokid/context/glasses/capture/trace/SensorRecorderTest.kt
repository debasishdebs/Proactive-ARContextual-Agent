package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** v0.9.7 B item 3: the recorder buffers down-sampled raw samples and writes them in batches; it has no connection to the classifier. */
class SensorRecorderTest {
    private val log = ArrayList<String>()
    private val clock = ManualClock(1_000)
    private var wall = 1_700_000_000_000L
    private class Fx(val root: File = TraceTestKit.tempDir()) { val store = SensorRecStore(File(root, "sensor_rec"), freeBytes = { Long.MAX_VALUE }) }
    private fun rec(fx: Fx, flushEvery: Int = 2_000, maxBuffered: Int = 6_000) =
        SensorRecorder(fx.store, clock = clock, wallClock = { wall }, io = { it.run() }, log = { log += it }, flushEvery = flushEvery, maxBuffered = maxBuffered)
    private fun written(fx: Fx): List<TraceEvent> = (fx.store.dir.listFiles() ?: emptyArray()).sortedBy { it.name }.flatMap { f ->
        TraceFiles.decodeFile(f.readText()).getOrThrow().let { c -> ChunkCodec.decode(c.traceId, c.seq, c.sha256, c.b64).getOrThrow().events }
    }
    /** [sec] seconds of a 50 Hz gyro and accel (sensor time = i * 20 ms). */
    private fun feed(r: SensorRecorder, sec: Int, from: Int = 0) { for (i in from until from + sec * 50) { clock.t += 20; r.onGyro(0.1f, 0.2f, 0.3f, i * 20L); r.onAccel(0.4f, 0.5f, 0.6f, i * 20L) } }

    @Test fun offRecordsNothingAndWritesNothing() {
        val fx = Fx(); val r = rec(fx)
        feed(r, 5); r.flush()
        assertTrue(written(fx).isEmpty()); assertEquals(0, r.buffered())
    }

    @Test fun samplesAreDownsampledToTheChosenRate() {
        for (hz in listOf(5, 10, 25)) {
            val fx = Fx(); val r = rec(fx); r.configure(hz)
            feed(r, 10); r.flush()
            val g = written(fx).filterIsInstance<TraceEvent.Gyro>(); val a = written(fx).filterIsInstance<TraceEvent.Accel>()
            assertTrue("$hz Hz gyro ${g.size}", g.size in (hz * 10 - 2)..(hz * 10 + 1)); assertTrue("$hz Hz accel ${a.size}", a.size in (hz * 10 - 2)..(hz * 10 + 1))
            assertTrue("never faster than the rate", g.zipWithNext().all { (x, y) -> y.sMs - x.sMs >= 1000L / hz - 20 })
        }
    }

    @Test fun stoppingFlushesWhatWasRecordedAndRecordsNoMore() {
        val fx = Fx(); val r = rec(fx); r.configure(10)
        feed(r, 3); r.configure(0)
        val n = written(fx).size
        assertTrue(n > 0); assertEquals(0, r.buffered())
        feed(r, 3, from = 200); r.flush()
        assertEquals("nothing after the stop", n, written(fx).size)
    }

    @Test fun aBatchIsWrittenWhenTheCountIsReachedAndOnTheMinuteTick() {
        val fx = Fx(); val r = rec(fx, flushEvery = 100); r.configure(25)
        feed(r, 5); assertTrue("nothing is written by the tap itself", written(fx).isEmpty())
        r.poll()
        assertTrue("the count flush ran on the beat", written(fx).size >= 100)
        val fx2 = Fx(); val r2 = rec(fx2); r2.configure(5)
        feed(r2, 2); assertTrue(written(fx2).isEmpty())
        clock.t += 61_000; r2.poll()
        assertTrue("the minute flush ran", written(fx2).isNotEmpty())
    }

    @Test fun discardDropsTheBufferWithoutWritingIt() {
        val fx = Fx(); val r = rec(fx); r.configure(10)
        feed(r, 3); assertTrue(r.buffered() > 0)
        r.discard(); r.flush()
        assertEquals(0, r.buffered()); assertTrue(written(fx).isEmpty())
    }

    @Test fun theBufferIsBoundedAndTheOverflowIsCountedNotKept() {
        val fx = Fx(); val r = rec(fx, flushEvery = 1_000_000, maxBuffered = 50); r.configure(25)
        feed(r, 10)
        assertEquals(50, r.buffered()); assertTrue(r.dropped() > 0)
    }

    @Test fun theLogLinesAreEventNamesOnly() {
        val fx = Fx(); val r = rec(fx); r.configure(10); feed(r, 2); r.configure(0)
        assertTrue(log.toString(), log.isNotEmpty() && log.all { it in setOf("sensor recording started", "sensor recording stopped", "sensor batch written", "sensor batch pruned", "sensor recording blocked") })
    }

    @Test fun aWriteFailureDropsTheBatchAndKeepsRecording() {
        val fx = Fx(); val r = SensorRecorder(SensorRecStore(File(fx.root, "sensor_rec"), freeBytes = { 0L }), clock = clock, wallClock = { wall }, io = { it.run() }, log = { log += it })
        r.configure(10); feed(r, 2); r.flush()
        assertEquals(0, r.buffered()); assertFalse(File(fx.root, "sensor_rec").listFiles()?.isNotEmpty() ?: false)
        feed(r, 1, from = 100); assertTrue(r.buffered() > 0)
    }

    // ---- the cleanup race: a batch taken under the lock but handed to the writer after a Forget completed ----
    private fun held(fx: Fx, queue: ArrayList<Runnable>) = SensorRecorder(fx.store, clock = clock, wallClock = { wall }, io = { queue += it }, log = { log += it })

    @Test fun aBatchTakenBeforeADiscardIsNeverWrittenAfterIt() {
        val fx = Fx(); val q = ArrayList<Runnable>(); val r = held(fx, q); r.configure(10)
        feed(r, 3); r.flush()                      // the batch is taken and queued, the writer has not run it yet
        assertEquals(1, q.size)
        r.discard()                                // the Forget arrives
        q.forEach { it.run() }                     // the writer reaches the batch only now
        assertTrue("forgotten samples must not come back: ${fx.store.dir.listFiles()?.map { it.name }}", written(fx).isEmpty())
    }

    @Test fun aBatchTakenBeforeAWipeOrRangeForgetOfTheStoreIsNeverWrittenAfterIt() {
        for (cleanup in listOf<(SensorRecStore) -> Unit>({ it.wipe() }, { it.forget(listOf(0L..Long.MAX_VALUE / 2)) })) {
            val fx = Fx(); val q = ArrayList<Runnable>(); val r = held(fx, q); r.configure(10)
            feed(r, 3); r.flush()
            cleanup(fx.store)
            q.forEach { it.run() }
            assertTrue(written(fx).isEmpty())
        }
    }

    @Test fun aBatchTakenAfterTheDiscardIsWrittenAsUsual() {
        val fx = Fx(); val q = ArrayList<Runnable>(); val r = held(fx, q); r.configure(10)
        feed(r, 3); r.discard(); feed(r, 3, from = 150); r.flush()
        q.forEach { it.run() }
        assertTrue(written(fx).isNotEmpty())
    }

    // ---- review round 2: the tap never waits, never throws; eligibility is immediate and going ineligible drops ----
    @Test fun theTapNeverWaitsForAStalledConfigure() {
        val fx = Fx(); val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val r = SensorRecorder(fx.store, clock = clock, wallClock = { wall }, io = { it.run() }, log = { if (it == "sensor recording started") { entered.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) } })
        val t = Thread { r.configure(10) }; t.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val done = java.util.concurrent.CountDownLatch(1)
            Thread { feed(r, 1); done.countDown() }.start()
            assertTrue("the sensor thread must not wait for configure()", done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); t.join(5_000) }
    }

    @Test fun aThrowInTheTapIsCountedAndNeverReachesTheClassifier() {
        val fx = Fx(); var boom = true
        val r = SensorRecorder(fx.store, clock = { if (boom) throw IllegalStateException("clock") else clock.t }, wallClock = { wall }, io = { it.run() }, log = {})
        r.configure(25)
        r.onGyro(0.1f, 0.2f, 0.3f, 100L, com.rokid.context.core.model.MotionState.STILL)
        r.onAccel(0.1f, 0.2f, 0.3f, 100L, 100L, com.rokid.context.core.model.MotionState.STILL)
        assertEquals(2L, r.errors()); boom = false
        r.onGyro(0.1f, 0.2f, 0.3f, 400L, com.rokid.context.core.model.MotionState.STILL); assertEquals(1, r.buffered())
    }

    @Test fun anIneligibleStateRecordsNothingAtOnceAndDropsWhatWasBufferedWithoutWritingIt() {
        val fx = Fx(); val r = rec(fx); r.configure(10)
        feed(r, 2); assertTrue(r.buffered() > 0)
        r.setEligible(false)
        assertEquals("dropped, not kept", 0, r.buffered())
        feed(r, 2, from = 100); assertEquals("a sample after the transition is ignored", 0, r.buffered())
        r.flush(); r.poll(); assertTrue("nothing was written", written(fx).isEmpty())
        r.setEligible(true); feed(r, 2, from = 200); r.flush()
        assertTrue("eligible again records", written(fx).isNotEmpty())
    }

    @Test fun theFullBufferDropsTheNewestSampleNotTheOldest() {
        val fx = Fx(); val r = rec(fx, flushEvery = 1_000_000, maxBuffered = 10); r.configure(25)
        feed(r, 5); r.flush()
        val g = written(fx).filterIsInstance<TraceEvent.Gyro>().map { it.sMs }
        assertTrue("kept the first samples: $g", g.isNotEmpty() && g.max() < 600)
        assertTrue(r.dropped() > 0)
    }
}
