package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.WatchState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchMotionIntakeTest {
    private var now = 10_000L
    private var on = true
    private val got = ArrayList<Evidence>()
    private val timers = ArrayList<Pair<Long, () -> Unit>>()
    private val persisted = ArrayList<Pair<Long, Long>>()
    private var epochResets = 0

    private fun intake(epoch: Long = 0, seq: Long = 0) = WatchMotionIntake(
        enabled = { on }, clock = { now }, sink = { synchronized(got) { got += it } },
        initialEpoch = epoch, initialSeq = seq, persist = { e, s -> persisted += e to s },
        schedule = { d, f -> timers += d to f }, onEpochReset = { epochResets++ },
    )

    private fun fire() { val t = timers.toList(); timers.clear(); t.forEach { it.second() } }

    @Test fun happyPath() {
        val i = intake(); val r = FakeWatch(i).send()
        assertTrue(r.ok); assertNull(r.reason)
        assertEquals(listOf<Evidence>(Evidence.WatchMotion(10_000L, WatchState.WALKING, 0.9f)), got)
    }

    @Test fun disabled() { on = false; assertEquals("not_enabled", FakeWatch(intake()).send().reason); assertTrue(got.isEmpty()) }

    @Test fun tooBig() {
        val big = "x".repeat(2049)
        assertEquals("too_big", intake().accept(big).reason)
        val ok = FakeWatch(intake()).json()
        assertTrue(intake().accept(ok + " ".repeat(2048 - ok.length)).ok)
    }

    @Test fun tooBigCountsBytesNotChars() =
        assertEquals("too_big", intake().accept("é".repeat(1100)).reason)

    @Test fun rateSecondWithin1s() {
        val i = intake(); val f = FakeWatch(i)
        assertTrue(f.send().ok); now += 500
        val r = f.send("STILL"); assertFalse(r.ok); assertEquals("rate", r.reason)
        assertEquals(1, got.size)
    }

    @Test fun rateCoalescesLatestAppliedWhenWindowOpens() {
        val i = intake(); val f = FakeWatch(i)
        assertTrue(f.send("WALKING").ok); now += 300
        assertEquals("rate", f.send("STILL").reason)
        assertEquals(700L, timers.single().first)
        now += 100
        assertEquals("rate", f.send("IN_VEHICLE").reason) // replaces the pending one
        now += 600; fire()
        assertEquals(2, got.size)
        assertEquals(WatchState.IN_VEHICLE, (got[1] as Evidence.WatchMotion).state)
        assertEquals(11_000L, (got[1] as Evidence.WatchMotion).atMs)
    }

    @Test fun pendingAppliedOnceOnly() {
        val i = intake(); val f = FakeWatch(i)
        f.send(); now += 300; f.send("STILL"); f.send("RUNNING")
        val actions = timers.map { it.second }; assertEquals(1, actions.size)
        now += 800; actions[0](); actions[0]()
        assertEquals(2, got.size)
    }

    @Test fun closeDropsPending() {
        val i = intake(); val f = FakeWatch(i)
        f.send(); now += 300; f.send("STILL"); i.close(); now += 800; fire()
        assertEquals(1, got.size)
    }

    @Test fun afterPendingAppliedNextMessageNeedsAFreshWindow() {
        val i = intake(); val f = FakeWatch(i)
        f.send(); now += 300; f.send("STILL"); now += 700; fire()
        now += 500
        assertEquals("rate", f.send("WALKING").reason)
    }

    @Test fun sameSeqIsStale() {
        val i = intake(); val f = FakeWatch(i)
        assertTrue(f.send(seq = 42).ok); now += 2000
        assertEquals("stale_seq", f.send(seq = 42).reason)
        assertEquals("stale_seq", f.send(seq = 41).reason)
    }

    @Test fun senderRestartNewEpochResetsSeq() {
        val i = intake(); val f = FakeWatch(i)
        assertTrue(f.send(seq = 42).ok); now += 2000
        assertTrue(f.send(seq = 1, epoch = 999L).ok); now += 2000
        assertEquals(1, epochResets)
        assertEquals("stale_seq", f.send(seq = 1, epoch = 999L).reason)
        assertEquals(999L to 1L, persisted.last())
    }

    @Test fun sameEpochLowSeqAfter42IsStale() {
        val i = intake(); val f = FakeWatch(i)
        assertTrue(f.send(seq = 42).ok); now += 2000
        assertEquals("stale_seq", f.send(seq = 1).reason)
    }

    @Test fun phoneRestartReplayIsStale() {
        val i = intake(epoch = 7193856231L, seq = 42)
        val f = FakeWatch(i)
        assertEquals("stale_seq", f.send(seq = 42).reason)
        assertEquals("stale_seq", f.send(seq = 7).reason)
        assertTrue(f.send(seq = 43).ok)
    }

    @Test fun acceptedIsPersisted() {
        val i = intake(); FakeWatch(i).send(seq = 5)
        assertEquals(listOf(7193856231L to 5L), persisted)
    }

    @Test fun versionTwo() {
        val i = intake()
        assertEquals("version", i.accept(FakeWatch(i).json(v = 2)).reason)
        assertTrue(got.isEmpty())
    }

    @Test fun garbage() {
        val i = intake()
        assertEquals("malformed", i.accept("not json").reason)
        assertEquals("malformed", i.accept(null).reason)
        assertEquals("malformed", i.accept("""{"v":1}""").reason)
        assertEquals("malformed", i.accept("[1]").reason)
    }

    @Test fun unknownStateIsUnknown() {
        val r = FakeWatch(intake()).send("SWIMMING")
        assertTrue(r.ok); assertEquals(WatchState.UNKNOWN, (got.single() as Evidence.WatchMotion).state)
    }

    @Test fun refreshedUnchangedStateGetsFreshEvidenceEveryTenMinutes() {
        val i = intake(); val f = FakeWatch(i)
        repeat(5) { assertTrue(f.send("WALKING").ok); now += 10 * 60_000L }
        assertEquals(5, got.size)
        assertEquals(listOf(10_000L, 610_000L, 1_210_000L, 1_810_000L, 2_410_000L), got.map { it.atMs })
    }

    @Test fun concurrentDeliveryAcceptsEachSeqOnce() {
        // rate is decided on the monotonic accept time: a frozen clock would make everything after the first a "rate" reject,
        // so the clock advances 1 s per read and the rate window never blocks, leaving exactly the seq/epoch guard to decide.
        val tick = AtomicInteger()
        val i = WatchMotionIntake(
            enabled = { true }, clock = { 1_000L * tick.incrementAndGet() },
            sink = { synchronized(got) { got += it } }, schedule = { _, _ -> },
        )
        val f = FakeWatch(i)
        val msgs = (1L..100L).map { f.json(seq = it) }
        val ok = AtomicInteger(); val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1); val done = CountDownLatch(16)
        repeat(16) {
            pool.execute {
                go.await()
                for (m in msgs) { val r = i.accept(m); if (r.ok) ok.incrementAndGet() }
                done.countDown()
            }
        }
        go.countDown(); done.await(); pool.shutdown()
        assertEquals(100, ok.get()); assertEquals(100, got.size)
    }

    @Test fun concurrentDeliveryWithFrozenClockAcceptsExactlyOne() {
        val i = WatchMotionIntake(
            enabled = { true }, clock = { 5_000L },
            sink = { synchronized(got) { got += it } }, schedule = { _, _ -> },
        )
        val f = FakeWatch(i)
        val msgs = (1L..100L).map { f.json(seq = it) }
        val ok = AtomicInteger(); val bad = AtomicInteger(); val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1); val done = CountDownLatch(16)
        repeat(16) {
            pool.execute {
                go.await()
                for (m in msgs) { val r = i.accept(m); if (r.ok) ok.incrementAndGet() else if (r.reason != "rate" && r.reason != "stale_seq") bad.incrementAndGet() }
                done.countDown()
            }
        }
        go.countDown(); done.await(); pool.shutdown()
        assertEquals(1, ok.get()); assertEquals(0, bad.get()); assertEquals(1, got.size)
    }

    @Test fun epochResetHookFiresOnlyWhenPreviousEpochWasNonZero() {
        val i = intake(); val f = FakeWatch(i)
        f.send(seq = 1); assertEquals(0, epochResets); now += 2000
        f.send(seq = 1, epoch = 5L); assertEquals(1, epochResets)
        val j = intake(epoch = 0, seq = 0); FakeWatch(j).send(seq = 1); assertEquals(1, epochResets)
    }
}
