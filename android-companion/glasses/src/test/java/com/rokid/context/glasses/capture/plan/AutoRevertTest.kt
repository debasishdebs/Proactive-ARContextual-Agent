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

    @Test fun threeCeilingHitsInTenMinutesRevert() {
        val a = AutoRevert()
        a.onCeilingHit(0); a.onCeilingHit(4 * min)
        assertNull(a.check(5 * min, planLive = true))
        a.onCeilingHit(9 * min + 59_000)
        assertEquals("auto:ceiling", a.check(9 * min + 59_000, planLive = true))
    }

    @Test fun twoHitsDoNot() {
        val a = AutoRevert(); a.onCeilingHit(0); a.onCeilingHit(1_000)
        assertNull(a.check(2_000, planLive = true))
    }

    @Test fun hitsOlderThanTenMinutesAgeOut() {
        val a = AutoRevert(); a.onCeilingHit(0); a.onCeilingHit(1_000); a.onCeilingHit(10 * min + 1)
        assertNull(a.check(10 * min + 1, planLive = true), "the first hit is 10 min old: only 2 inside the window")
    }

    @Test fun theSettingsPathAndShadowNeverRevert() {
        val a = AutoRevert(); repeat(5) { a.onCeilingHit(it * 1_000L) }
        assertNull(a.check(6_000, planLive = false), "no plan in force: the ceiling is the settings' own")
    }

    @Test fun aRevertClearsTheHitsSoItFiresOnce() {
        val a = AutoRevert(); repeat(3) { a.onCeilingHit(it * 1_000L) }
        assertEquals("auto:ceiling", a.check(3_000, planLive = true))
        assertNull(a.check(3_100, planLive = true))
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
