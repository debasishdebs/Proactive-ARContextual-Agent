package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import com.rokid.context.glasses.capture.plan.PlanTestKit.withRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Codex final pass findings 11-14 (glasses side). */
class PlanFinalPassTest {
    private var now = 1_000L
    private val mem = MemPersistence()
    private val sent = ArrayList<CapturePlanAck>()
    private val host = FakeHost()
    private fun ctl() = PlanController(mem, { now }, { "bc:1" }, { ContextState.UNKNOWN }, { sent += it }).also { it.attach(host) }
    private val a = PlanTestKit.plan(PlanTestKit.plan().config.let { it.copy(gate = it.gate.copy(hamming = 14)) })
    private val b = PlanTestKit.plan(withRow(PlanTestKit.plan().config, PlanState.REST) { it.copy(capPerMin = 1) })

    // ---- 11: expiry enforced at the point of use, with no tick ----
    @Test fun expiredTrialIsNotReadAfterTheSupervisorIsGone() {
        val c = ctl()
        c.onMessage(trial(1, a, max = 60_000))
        assertEquals(a.config.gate.hamming, c.active.gate.hamming)
        now += 59_999                                          // no tick at all: the supervisor was cancelled
        assertEquals(a.config.gate.hamming, c.active.gate.hamming)
        now += 1
        assertSame(ActiveConfig.SETTINGS, c.active, "past the limit the accessor falls back to settings")
        assertSame(ActiveConfig.SETTINGS, c.active)
        c.tick(now)                                            // when a tick does come, the revert is still reported
        assertEquals("auto:trial_end", sent.last().reason)
    }

    // ---- 12: running A's ceiling verdict never touches staged B ----
    @Test fun ceilingVerdictOfRunningARevertsOnlyAAndLeavesStagedBActivatable() {
        val c = ctl()
        c.onMessage(trial(1, a)); val idA = c.runningId()!!
        host.quiet = false
        c.onMessage(trial(2, b))                               // B staged during a non-quiet bout
        sent.clear()
        val ack = c.revertIf(idA, "auto:ceiling")
        assertNotNull(ack)
        assertEquals(1L, ack.gen); assertEquals(PlanAckState.REVERTED, ack.state)
        assertTrue(sent.none { it.gen == 2L && it.state == PlanAckState.REVERTED }, "B never got a verdict: ${sent.map { it.gen to it.state }}")
        assertNull(c.runningId())
        host.quiet = true
        c.tick(now)
        val eff = sent.single { it.state == PlanAckState.EFFECTIVE }
        assertEquals(2L, eff.gen)
        assertEquals(2L, c.runningId()!!.gen)
        assertEquals(1, c.active.table.getValue(ContextState.REST).capPerMin)
    }

    // ---- 13: install is part of the staged identity ----
    @Test fun replacingPendingInstallAWithInstallBSameGenRunsB() {
        val c = ctl()
        host.quiet = false
        c.onMessage(trial(1, a).copy(phoneInstall = "A"))                // staged, waiting
        c.onMessage(trial(1, b).copy(phoneInstall = "B"))                // replaces it: same gen, other install
        host.quiet = true
        c.tick(now)
        val eff = sent.single { it.state == PlanAckState.EFFECTIVE }
        assertEquals("B", eff.phoneInstall)
        assertEquals(b.configHash, eff.effectiveHash)
        assertEquals(b.config.policy.getValue(PlanState.REST).capPerMin, c.active.table.getValue(ContextState.REST).capPerMin)
        assertEquals("B", c.runningId()!!.install)
        assertNotEquals(a.config.gate.hamming, c.active.gate.hamming)
    }

    // ---- 14: the ack stamp follows the link session, not a numeric maximum ----
    @Test fun stampFollowsANewLinkSessionEvenWhenThePhoneClockMovedBack() {
        val c = ctl()
        c.onMessage(trial(1, a).copy(sessionStartMs = 9_000L))
        assertEquals(9_000L, c.statusAck().sessionStartMs)
        c.onLinkSession()                                      // reconnect
        c.onMessage(trial(1, a).copy(sessionStartMs = 2_000L)) // phone clock moved an hour back
        assertEquals(2_000L, c.statusAck().sessionStartMs)
        sent.clear()
        now += 60_000; c.tick(now)
        assertEquals(2_000L, sent.single().sessionStartMs, "self-emitted ack matches the phone's current session")
        assertTrue(PhoneGateRule.matches(sent.single(), "p1", 2_000L), "the phone's PlanAckGate rule accepts it")
    }

    @Test fun delayedOldSessionMessageInsideTheSameLinkSessionStillDoesNotMoveTheStamp() {
        val c = ctl()
        c.onLinkSession()
        c.onMessage(trial(1, a).copy(sessionStartMs = 900L))
        c.onMessage(trial(1, a).copy(sessionStartMs = 500L))
        assertEquals(900L, c.statusAck().sessionStartMs)
    }
}
