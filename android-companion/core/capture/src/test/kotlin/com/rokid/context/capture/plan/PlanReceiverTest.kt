package com.rokid.context.capture.plan

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
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
        for (mode in listOf(PlanMode.TRIAL, PlanMode.SHADOW)) {
            assertEquals("schema", r.onMessage(msg(1, mode, 60_000, json = null)).reason)
            assertEquals("schema", r.onMessage(msg(1, mode, 60_000, hash = null)).reason)
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
        trial(r, 1, 60_000)
        // the phone's wall clock was rolled back an hour: a re-sent duplicate now carries an old atMs and a far-future trialEndsAtMs
        val rolledBack = CapturePlanMessage(gen = 1, mode = PlanMode.TRIAL, configHash = plan.configHash, planJson = text, trialMaxMs = 60_000, trialEndsAtMs = 9_999_999_999, phoneInstall = "p1", atMs = 1)
        clock = 40_000
        assertEquals(PlanAckState.EFFECTIVE, r.onMessage(rolledBack).state)
        assertEquals(10_000L, mem.s!!.trialStartElapsed)
        assertNull(r.tick(ContextState.REST, 69_999))
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

    @Test fun liveRefusedWithoutCap() {
        val r = rx()
        assertEquals(false, "live" in PlanReceiver.CAPS)
        val a = r.onMessage(msg(1, PlanMode.LIVE))
        assertEquals(PlanAckState.REJECTED, a.state); assertEquals("unsupported:live", a.reason); assertEquals(PlanMode.LIVE, a.mode)
        assertNull(mem.s); assertEquals(0, mem.commits)
        // after a staged SHADOW, a LIVE with a newer gen still stages nothing
        r.onMessage(msg(2)); r.activated(2)
        assertEquals("unsupported:live", r.onMessage(msg(3, PlanMode.LIVE)).reason)
        assertEquals(2, mem.s!!.appliedGen); assertEquals(PlanMode.SHADOW, mem.s!!.mode)
    }

    @Test fun terminalReAckKeepsOriginalModeAndPublishId() {
        val r = rx()
        r.onMessage(msg(2, PlanMode.TRIAL, 60_000).copy(publishId = "pub-7")); r.activated(2)
        r.revert("auto:ceiling")
        val a = r.onMessage(msg(2, PlanMode.TRIAL, 60_000).copy(publishId = "pub-other"))
        assertEquals(PlanAckState.REVERTED, a.state); assertEquals("auto:ceiling", a.reason)
        assertEquals(PlanMode.TRIAL, a.mode); assertEquals("pub-7", a.publishId)
    }

    @Test fun secondRevertKeepsTheFirstReason() {
        val r = rx()
        trial(r, 1)
        assertEquals("auto:ceiling", r.revert("auto:ceiling").reason)
        val again = r.revert("auto:trial_end")
        assertEquals("auto:ceiling", again.reason); assertEquals(PlanAckState.REVERTED, again.state)
        assertEquals("auto:ceiling", mem.s!!.terminal[1L])
        assertEquals("auto:ceiling", r.onMessage(msg(1, PlanMode.TRIAL, 60_000)).reason)
    }

    @Test fun activatedTickAndRevertAcksCarryPublishId() {
        val r = rx()
        clock = 10_000
        r.onMessage(msg(1, PlanMode.TRIAL, 60_000).copy(publishId = "pub-1"))
        assertEquals("pub-1", r.activated(1).publishId)
        assertEquals("pub-1", r.tick(ContextState.REST, 70_000)!!.publishId)
        val r2 = rx()
        r2.onMessage(msg(2).copy(publishId = "pub-2")); r2.activated(2)
        assertEquals("pub-2", r2.revert("auto:ceiling").publishId)
    }

    @Test fun bootIdIsReadOnceNotPerTick() {
        var reads = 0
        val r = PlanReceiver(mem, { clock }, { reads++; boot })
        trial(r, 1, 3_600_000)
        val before = reads
        repeat(100) { assertNull(r.tick(ContextState.REST, clock + 1_000)) }
        assertEquals(before, reads)
        assertEquals(1, reads)
    }

    @Test fun multibyteUtf8BoundaryThroughTheReceiver() {
        val r = rx()
        val ok = PlanFixtures.planWithBytes(8_192)
        val okText = PlanJson.encode(ok)
        assertEquals(8_192, okText.toByteArray(Charsets.UTF_8).size); assertEquals(true, okText.length < 8_192)
        assertEquals(PlanAckState.STAGED, r.onMessage(msg(1, json = okText, hash = ok.configHash)).state)
        val big = PlanFixtures.planWithBytes(8_193)
        val bigText = PlanJson.encode(big)
        assertEquals(true, bigText.length <= 8_192)   // a char-length check would let it through
        assertEquals("too_big", r.onMessage(msg(2, json = bigText, hash = big.configHash)).reason)
    }

    @Test fun deepNestingRejectedNotCrash() {
        val r = rx()
        val deep = "{\"planSchema\":1,\"x\":" + "[".repeat(4_000) + "]".repeat(4_000) + "}"
        val a = r.onMessage(msg(1, json = deep))
        assertEquals(PlanAckState.REJECTED, a.state)
        assertNull(mem.s)
    }

    @Test fun revertFailsClosedWhenTheWriteFails() {
        class Flaky : PlanPersistence {
            var s: ReceiverState? = null; var fail = false
            override fun load() = s
            override fun commit(s: ReceiverState) { if (fail) throw java.io.IOException("disk"); this.s = s }
        }
        val p = Flaky(); val r = PlanReceiver(p, { clock }, { boot })
        r.onMessage(msg(1, PlanMode.TRIAL, 60_000)); r.activated(1)
        p.fail = true
        val a = r.revert("auto:ceiling")                      // must not throw
        assertEquals(PlanAckState.REVERTED, a.state)
        assertEquals(PlanMode.SETTINGS, r.current!!.mode)     // reverted in memory
        assertEquals("auto:ceiling", r.current!!.terminal[1])
        assertEquals(true, r.persistPending)
        assertEquals(PlanMode.TRIAL, p.s!!.mode)              // the disk still has the TRIAL
        p.fail = false
        r.tick(ContextState.REST, clock)                      // the retry
        assertEquals(false, r.persistPending)
        assertEquals("auto:ceiling", p.s!!.terminal[1])
    }
}
