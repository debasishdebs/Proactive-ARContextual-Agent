package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlanReceiverTest {
    private class Mem(var s: ReceiverState? = null) : PlanPersistence {
        var commits = 0
        override fun load() = s
        override fun commit(s: ReceiverState) { this.s = s; commits++ }
    }

    private var clock = 1_000L
    private var boot = "boot1"
    private val mem = Mem()
    private fun rx() = PlanReceiver(mem, { clock }, { boot })

    private val plan = PlanFixtures.plan()
    private val text = PlanFixtures.text(plan)
    private fun msg(gen: Long, mode: PlanMode = PlanMode.SHADOW, trialMaxMs: Long? = null, json: String? = text, hash: String? = plan.configHash, install: String = "p1") =
        CapturePlanMessage(gen = gen, mode = mode, configHash = hash, planJson = json, trialMaxMs = trialMaxMs, phoneInstall = install, atMs = 5)

    private fun trial(r: PlanReceiver, gen: Long = 1, max: Long = 60_000) {
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(gen, PlanMode.TRIAL, max)).state)
        assertEquals(PlanAckState.EFFECTIVE, r.activated(gen).state)
    }

    @Test fun staleRejected() {
        val r = rx()
        r.onMessage(msg(5)); r.activated(5)
        val a = r.onMessage(msg(4))
        assertEquals(PlanAckState.REJECTED, a.state); assertEquals("stale", a.reason)
        assertEquals(5, mem.s!!.appliedGen)
    }

    @Test fun duplicateReackedNotReapplied() {
        val r = rx()
        r.onMessage(msg(3))
        val c = mem.commits
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(3)).state)
        r.activated(3)
        val d = r.onMessage(msg(3))
        assertEquals(PlanAckState.EFFECTIVE, d.state)
        assertEquals(plan.configHash, d.effectiveHash)
        assertEquals(c + 1, mem.commits)   // only the activation committed
    }

    @Test fun terminalGenNeverReapplied() {
        val r = rx()
        trial(r, 2)
        r.revert("auto:ceiling")
        val a = r.onMessage(msg(2, PlanMode.TRIAL, 60_000))
        assertEquals(PlanAckState.REVERTED, a.state); assertEquals("auto:ceiling", a.reason)
        assertEquals(PlanMode.SETTINGS, mem.s!!.mode)
        assertEquals(PlanAckState.REVERTED, r.onMessage(msg(2)).state)
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(3)).state)   // a newer gen is fine
    }

    @Test fun trialNeedsMaxMs() {
        val r = rx()
        val a = r.onMessage(msg(1, PlanMode.TRIAL, null))
        assertEquals("bounds", a.reason)
        assertEquals("bounds", r.onMessage(msg(1, PlanMode.TRIAL, 0)).reason)
        assertNull(mem.s)
    }

    @Test fun trialMaxOver60MinRejected() {
        val r = rx()
        assertEquals("bounds", r.onMessage(msg(1, PlanMode.TRIAL, 3_600_001)).reason)
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(1, PlanMode.TRIAL, 3_600_000)).state)
    }

    @Test fun planModesNeedPlanJsonAndHash() {
        val r = rx()
        for (mode in listOf(PlanMode.LIVE, PlanMode.SHADOW)) {
            assertEquals("schema", r.onMessage(msg(1, mode, json = null)).reason)
            assertEquals("schema", r.onMessage(msg(1, mode, hash = null)).reason)
        }
        assertEquals("schema", r.onMessage(msg(1, hash = "000000000000")).reason)
        assertEquals("schema", r.onMessage(msg(1, json = "{not json")).reason)
        assertEquals(PlanAckState.STAGED, r.onMessage(CapturePlanMessage(gen = 1, mode = PlanMode.SETTINGS, phoneInstall = "p1")).state)
    }

    @Test fun tooBigAndBoundsAndVersion() {
        val r = rx()
        assertEquals("too_big", r.onMessage(msg(1, json = " ".repeat(8_193) + text)).reason)
        val bad = PlanFixtures.plan(PlanFixtures.row(PlanResolver.identity(), PlanState.REST) { it.copy(capPerMin = 9) })
        assertEquals("bounds", r.onMessage(msg(1, json = PlanFixtures.text(bad), hash = bad.configHash)).reason)
        val v2 = text.replace("\"planSchema\":1", "\"planSchema\":2")
        assertEquals("version", r.onMessage(msg(1, json = v2)).reason)
    }

    @Test fun stagedNotEffectiveUntilActivated() {
        val r = rx()
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(1)).state)
        assertEquals(false, mem.s!!.effective)
        assertEquals(PlanAckState.EFFECTIVE, r.activated(1).state)
        assertEquals(true, mem.s!!.effective)
        assertEquals(PlanAckState.REJECTED, r.activated(9).state)
    }

    @Test fun trialEndsOnElapsedNotWallClock() {
        val r = rx()
        clock = 10_000; trial(r, 1, 60_000)
        assertNull(r.tick(ContextState.REST, 69_999))
        val a = assertNotNull(r.tick(ContextState.REST, 70_000))
        assertEquals(PlanAckState.REVERTED, a.state); assertEquals("auto:trial_end", a.reason)
        assertEquals(PlanMode.SETTINGS, mem.s!!.mode)
        assertNull(r.tick(ContextState.REST, 1_000_000))
    }

    @Test fun wallClockRollbackDoesNotExtend() {
        val r = rx()
        clock = 10_000
        // the phone's display-only wall clock fields are garbage; only the glasses' elapsed clock counts
        r.onMessage(CapturePlanMessage(gen = 1, mode = PlanMode.TRIAL, configHash = plan.configHash, planJson = text, trialMaxMs = 60_000, trialEndsAtMs = 9_999_999_999, phoneInstall = "p1", atMs = 1))
        r.activated(1)
        assertNull(r.tick(ContextState.REST, 69_000))
        assertEquals("auto:trial_end", r.tick(ContextState.REST, 70_000)!!.reason)
    }

    @Test fun duplicateDoesNotExtendTrial() {
        val r = rx()
        clock = 10_000; trial(r, 1, 60_000)
        clock = 50_000
        r.onMessage(msg(1, PlanMode.TRIAL, 60_000))
        r.activated(1)
        assertEquals(10_000L, mem.s!!.trialStartElapsed)
        assertEquals("auto:trial_end", r.tick(ContextState.REST, 70_000)!!.reason)
    }

    @Test fun trialClockStartsAtActivationNotStaging() {
        val r = rx()
        clock = 10_000
        r.onMessage(msg(1, PlanMode.TRIAL, 60_000))
        assertNull(r.tick(ContextState.REST, 500_000))   // never activated: not running
        clock = 20_000; r.activated(1)
        assertEquals(20_000L, mem.s!!.trialStartElapsed)
    }

    @Test fun rebootDuringTrialReverts() {
        val r = rx()
        trial(r, 1, 3_600_000)
        boot = "boot2"
        val again = PlanReceiver(mem, { clock }, { boot })   // restarted process reads the persisted state
        val a = assertNotNull(again.tick(ContextState.REST, clock + 1))
        assertEquals("auto:reboot", a.reason)
        assertEquals(PlanAckState.REVERTED, again.onMessage(msg(1, PlanMode.TRIAL, 3_600_000)).state)
    }

    @Test fun otherPhoneInstallRestartsGenCounter() {
        val r = rx()
        r.onMessage(msg(50)); r.activated(50)
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(1, install = "p2")).state)
    }
}
