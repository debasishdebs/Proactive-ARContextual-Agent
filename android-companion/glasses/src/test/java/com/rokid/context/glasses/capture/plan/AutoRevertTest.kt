package com.rokid.context.glasses.capture.plan

import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.plan.PlanTestKit.settings
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoRevertTest {
    private val min = 60_000L
    private val A = PlanId("p1", 1, PlanMode.TRIAL)
    private val B = PlanId("p1", 2, PlanMode.TRIAL)

    @Test fun threeCeilingHitsInTenMinutesRevert() {
        val a = AutoRevert()
        a.onCeilingHit(0, A); a.onCeilingHit(4 * min, A)
        assertNull(a.check(5 * min, A))
        a.onCeilingHit(9 * min + 59_000, A)
        assertEquals("auto:ceiling", a.check(9 * min + 59_000, A))
    }

    @Test fun twoHitsDoNot() {
        val a = AutoRevert(); a.onCeilingHit(0, A); a.onCeilingHit(1_000, A)
        assertNull(a.check(2_000, A))
    }

    @Test fun hitsOlderThanTenMinutesAgeOut() {
        val a = AutoRevert(); a.onCeilingHit(0, A); a.onCeilingHit(1_000, A); a.onCeilingHit(10 * min + 1, A)
        assertNull(a.check(10 * min + 1, A), "the first hit is 10 min old: only 2 inside the window")
    }

    @Test fun hitsWithNoRunningPlanNeverCountAgainstALaterOne() {
        val a = AutoRevert(); repeat(5) { a.onCeilingHit(it * 1_000L, null) }   // the settings path or SHADOW
        a.onCeilingHit(6_000, A)
        assertNull(a.check(6_000, A), "settings hits are dropped, not carried into the TRIAL that starts next")
        assertNull(a.check(6_000, null))
    }

    @Test fun hitsOfPlanADoNotCountAgainstBWhateverTheBeatsSaw() {
        val a = AutoRevert(); a.onCeilingHit(0, A); a.onCeilingHit(1_000, A)   // no check ran between A and B (supervisor beat missed the swap)
        a.onCeilingHit(2_000, B)
        assertNull(a.check(2_000, B), "two A hits plus one B hit is one hit for B")
        a.onCeilingHit(3_000, B); a.onCeilingHit(4_000, B)
        assertEquals("auto:ceiling", a.check(4_000, B))
    }

    @Test fun aRevertClearsTheHitsSoItFiresOnce() {
        val a = AutoRevert(); repeat(3) { a.onCeilingHit(it * 1_000L, A) }
        assertEquals("auto:ceiling", a.check(3_000, A))
        assertNull(a.check(3_100, A))
    }

    // ---- through the controller ----

    private var now = 1_000L
    private val mem = MemPersistence()
    private val acks = Acks()
    private fun ctl(m: MemPersistence = mem, boot: String = "bc:1", h: FakeHost? = FakeHost()) =
        PlanController(m, { now }, { boot }, { ContextState.UNKNOWN }, { acks.sent += it }).also { it.host = h }
    private val tuned = PlanTestKit.plan(PlanTestKit.plan().config.let { it.copy(gate = it.gate.copy(hamming = 14)) })

    @Test fun aCeilingRevertGoesBackToSettingsAndReportsIt() {
        val c = ctl()
        c.onMessage(trial(1, tuned, max = 60_000))
        assertTrue(c.active !== ActiveConfig.SETTINGS)
        val ack = c.revert("auto:ceiling")!!
        assertEquals(PlanAckState.REVERTED, ack.state); assertEquals("auto:ceiling", ack.reason)
        assertSame(ActiveConfig.SETTINGS, c.active)
        assertEquals("auto:ceiling", c.status().revert)
        assertEquals(PlanMode.SETTINGS, c.status().mode)
    }

    @Test fun aStaleVerdictNeverRevertsTheNewPlan() {
        val c = ctl()
        c.onMessage(trial(1, tuned, max = 60_000))
        val a = c.runningId()!!
        c.onMessage(trial(2, tuned, max = 60_000))
        assertEquals(2L, c.runningId()!!.gen)
        assertNull(c.revertIf(a, "auto:ceiling"), "plan A is gone")
        assertTrue(c.planLive())
        assertEquals("auto:ceiling", c.revertIf(c.runningId()!!, "auto:ceiling")!!.reason)
    }

    @Test fun aStagedHandoverKeepsTheRunningTrialProtected() {
        val host = FakeHost()
        val c = ctl(h = host)
        c.onMessage(trial(1, tuned, max = 600_000))
        val a = c.runningId()!!
        assertEquals(1L, a.gen)
        host.quiet = false                                            // the handover to SETTINGS gen 2 has to wait
        c.onMessage(settings(2))
        assertTrue(c.planLive(), "TRIAL A is still what captures")
        assertEquals(a, c.runningId())
        assertEquals(PlanMode.TRIAL, c.status().mode); assertEquals(1L, c.status().gen)
        assertTrue(c.active !== ActiveConfig.SETTINGS)
        val guard = AutoRevert(); repeat(3) { guard.onCeilingHit(now, c.runningId()) }
        assertEquals("auto:ceiling", guard.check(now, c.runningId()), "A's ceiling accounting survives the staged replacement")
        assertEquals(PlanAckState.REVERTED, c.revertIf(a, "auto:ceiling")!!.state)
        assertFalse(c.planLive())
    }

    @Test fun theRunningTrialEndsOnItsOwnLimitWhileAReplacementKeepsFailingToActivate() {
        val failing = object : MemPersistence() {
            var failAll = false
            override fun commit(s: com.rokid.context.capture.plan.ReceiverState) { if (failAll) throw java.io.IOException("disk full"); super.commit(s) }
        }
        val host = FakeHost()
        val c = ctl(failing, h = host)
        c.onMessage(trial(1, tuned, max = 60_000))                     // running from elapsed 1 000: ends at 61 000
        host.quiet = false
        c.onMessage(settings(2))                                       // staged; waits for quiet
        failing.failAll = true                                         // from now on every activation write fails
        host.quiet = true
        for (i in 1..30) { now += 1_000; c.tick(now) }                 // 31 000: still the same TRIAL, still protected
        assertTrue(c.planLive())
        assertEquals(1L, c.runningId()!!.gen)
        for (i in 1..31) { now += 1_000; c.tick(now) }                 // past 61 000: the TRIAL limit holds although the receiver's clock moved on
        assertFalse(c.planLive())
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun planIsLiveOnlyForARunningTrial() {
        val c = ctl()
        assertFalse(c.planLive())
        c.onMessage(trial(1, tuned, max = 60_000))
        assertTrue(c.planLive())
        c.revert("auto:ceiling")
        assertFalse(c.planLive())
    }

    // ---- carried in from task 11: a revert whose write failed, then the process died ----

    @Test fun aTrialResumedFromDiskStillEndsAtItsPersistedStart() {
        val first = ctl()
        now = 1_000; first.onMessage(trial(1, tuned, max = 60_000))        // activated at elapsed 1000 (persisted start)
        mem.failNextCommit = true
        first.revert("auto:ceiling")                                       // in memory: reverted; on disk: still an effective TRIAL
        assertSame(ActiveConfig.SETTINGS, first.active)
        // the process dies before the write is retried; a new process starts 30 s later
        now = 31_000
        val second = ctl()
        assertTrue(second.active !== ActiveConfig.SETTINGS, "the persisted TRIAL resumes (bounded)")
        now = 60_999; second.tick(now)
        assertTrue(second.active !== ActiveConfig.SETTINGS)
        now = 61_000; second.tick(now)                                     // 60 s after the PERSISTED start, not after the restart
        assertSame(ActiveConfig.SETTINGS, second.active)
        assertEquals("auto:trial_end", second.status().revert)
    }

    @Test fun aRestartPastThePersistedLimitNeverAppliesTheTrial() {
        val first = ctl()
        now = 1_000; first.onMessage(trial(1, tuned, max = 60_000))
        mem.failNextCommit = true; first.revert("auto:ceiling")
        now = 120_000
        val second = ctl()
        assertSame(ActiveConfig.SETTINGS, second.active)
        assertEquals("auto:trial_end", second.status().revert)
    }

    @Test fun aRebootNeverResumesTheTrial() {
        val first = ctl()
        now = 1_000; first.onMessage(trial(1, tuned, max = 60_000))
        mem.failNextCommit = true; first.revert("auto:ceiling")
        now = 5_000
        val second = ctl(boot = "bc:2")
        assertSame(ActiveConfig.SETTINGS, second.active)
        assertEquals("auto:reboot", second.status().revert)
    }

    @Test fun theFailedRevertWriteIsRetriedAtTheNextTick() {
        val c = ctl()
        c.onMessage(trial(1, tuned, max = 60_000))
        mem.failNextCommit = true; c.revert("auto:ceiling")
        now += 1_000; c.tick(now)
        assertEquals(PlanMode.SETTINGS, mem.state!!.mode, "the revert reached the disk")
        assertEquals("auto:ceiling", mem.state!!.terminal[1])
    }
}
