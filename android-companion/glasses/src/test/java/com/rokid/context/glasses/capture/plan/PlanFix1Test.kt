package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanReceiver
import com.rokid.context.capture.plan.PlanState
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The phone's PlanAckGate rule (services:glasses-link, minSdk 29, so not a dependency of this minSdk 28 module; the real classes are
 * exercised on the same ack shapes in Slice3aWireTest.stampedReceiverAcksMatchAndTheCapsAckStaysCapsOnly).
 */
object PhoneGateRule {
    fun matches(a: CapturePlanAck, install: String, session: Long) = a.phoneInstall == install && a.sessionStartMs == session
    fun isCaps(a: CapturePlanAck) = a.gen == 0L && a.phoneInstall.isEmpty() && a.sessionStartMs == 0L && a.reason == null
}

/** Task 11 fix round 1: ack stamping, fail-safe persistence, attach, backoff, TRIAL check in statusAck. */
class PlanFix1Test {
    private var now = 1_000L
    private var boot = "bc:1"
    private val mem = MemPersistence()
    private val sent = ArrayList<CapturePlanAck>()
    private val host = FakeHost()
    private fun ctl(m: MemPersistence = mem, h: FakeHost? = host) =
        PlanController(m, { now }, { boot }, { ContextState.UNKNOWN }, { sent += it }).also { if (h != null) it.attach(h) }
    private val tuned = PlanTestKit.plan(PlanTestKit.plan().config.let { it.copy(gate = it.gate.copy(hamming = 14)) })
    private fun m(gen: Long, session: Long = 777L, max: Long = 60_000L) = trial(gen, tuned, max).copy(sessionStartMs = session)

    // ---- Important 1 ----

    @Test fun everyAckIsStampedWithTheSessionAndMatchesThePhoneGate() {
        fun ok(a: CapturePlanAck) = PhoneGateRule.matches(a, "p1", 777L)
        val c = ctl()
        val first = c.onMessage(m(1))
        assertTrue(ok(first), "the returned ack")
        assertEquals(listOf("STAGED", "EFFECTIVE"), sent.map { it.state.name })
        for (a in sent) assertTrue(ok(a), "${a.state}")
        sent.clear()
        now += 60_000; c.tick(now)                                  // self-emitted: auto:trial_end
        assertEquals("auto:trial_end", sent.single().reason)
        assertTrue(ok(sent.single()))
        assertTrue(ok(c.statusAck()), "the status ack of the session")
    }

    @Test fun aRevertAtLoadIsStampedWithTheNextSessionOnItsStatusAck() {
        ctl().onMessage(m(1))
        boot = "bc:2"
        val c2 = ctl(mem, null)
        c2.onMessage(m(2, session = 900L))                          // the new session's first message teaches the stamp
        assertEquals(900L, c2.statusAck().sessionStartMs)
    }

    @Test fun theHeldPlanTravelsAsAStatusAckNotAsTheAnnouncement() {
        val c = ctl(); c.onMessage(m(1)); c.revert("auto:ceiling")
        val status = c.statusAck()
        assertEquals(1L, status.gen); assertEquals(PlanAckState.REVERTED, status.state)
        assertTrue(PhoneGateRule.matches(status, "p1", 777L))
        assertTrue(!PhoneGateRule.isCaps(status), "a status ack is never mistaken for the caps announcement")
        assertTrue(PhoneGateRule.isCaps(CapturePlanAck(gen = 0, caps = PlanReceiver.CAPS)), "and the announcement shape stays caps-only")
    }

    // ---- Important 3 ----

    @Test fun aFailedWriteAtTrialEndStillLeavesSettingsActive() {
        val c = ctl(); c.onMessage(m(1)); assertTrue(c.active !== ActiveConfig.SETTINGS)
        sent.clear()
        now += 60_000; mem.failNextCommit = true
        c.tick(now)
        assertSame(ActiveConfig.SETTINGS, c.active, "fail closed")
        assertEquals("auto:trial_end", sent.single().reason)
        now += 1_000; c.tick(now)                                    // the persist is retried
        assertEquals("auto:trial_end", mem.state!!.terminal[1])
    }

    @Test fun aFailedWriteAtLoadDoesNotCrashAndStaysOnSettings() {
        ctl().onMessage(m(1))
        boot = "bc:2"; mem.failNextCommit = true
        val c = ctl(mem, null)
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun aFailedWriteInOnMessageDoesNotThrow() {
        val c = ctl(); mem.failNextCommit = true
        val a = c.onMessage(m(1))
        assertEquals(PlanAckState.REJECTED, a.state)
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun aFailedWriteInOnPhoneSettingsDoesNotThrowAndFailsClosed() {
        val c = ctl(); c.onMessage(m(1)); mem.failNextCommit = true
        c.onPhoneSettings(planAware = false)
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    // ---- Minor 1, 2, 4, 10 ----

    @Test fun attachInstallsTheConfigInForce() {
        val c = ctl(h = null)
        c.onMessage(m(1))                                            // activated while no session host exists
        val h = FakeHost(); c.attach(h)
        assertSame(c.active, h.installs.single())
    }

    @Test fun aFailedActivationWriteForcesAtMostOncePer10s() {
        val big = object : MemPersistence() {
            override fun commit(s: com.rokid.context.capture.plan.ReceiverState) { if (s.effective && s.mode == PlanMode.TRIAL) throw java.io.IOException("x"); super.commit(s) }
        }
        val c2 = ctl(big, host)
        c2.onMessage(m(1))                                           // staged; activation waits (not quiet)
        host.quiet = false
        val before = host.aborts
        for (i in 1..30) { now += 1_000; host.quiet = false; c2.tick(now) }
        assertTrue(host.aborts - before <= 3, "aborts over 30 s: ${host.aborts - before}")
    }

    @Test fun statusAckPastTheTrialLimitReportsRevertedAndFallsBack() {
        val c = ctl(); c.onMessage(m(1)); now += 60_000
        val s = c.statusAck()
        assertEquals(PlanAckState.REVERTED, s.state); assertEquals("auto:trial_end", s.reason)
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun anAbsentEditableRowIsExactlyTheUnknownRowPin() {
        val cfg = tuned.config.copy(policy = tuned.config.policy - PlanState.REST - PlanState.FLIGHT)
        val a = ActiveConfig.of(PlanTestKit.plan(cfg))
        val unknown = a.table.getValue(ContextState.UNKNOWN)
        assertEquals(unknown, a.table.getValue(ContextState.REST)); assertEquals(unknown, a.table.getValue(ContextState.FLIGHT))
    }

    @Test fun trialIsSettleWakeWhateverTheSettingsSay() {
        val a = ActiveConfig.of(tuned)
        for (w in com.rokid.context.core.model.CameraWake.entries)
            assertEquals(com.rokid.context.core.model.CameraWake.SETTLE, a.profile(w, com.rokid.context.glasses.capture.session.BudgetTier.T0, ContextState.UNKNOWN).wake)
        assertEquals(com.rokid.context.core.model.CameraWake.MOTION, ActiveConfig.SETTINGS.profile(com.rokid.context.core.model.CameraWake.MOTION, com.rokid.context.glasses.capture.session.BudgetTier.T0, ContextState.UNKNOWN).wake)
    }
}
