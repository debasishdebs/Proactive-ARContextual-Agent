package com.rokid.context.glasses.capture.plan

import com.rokid.context.capture.plan.PlanReceiver
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.plan.PlanTestKit.settings
import com.rokid.context.glasses.capture.plan.PlanTestKit.shadow
import com.rokid.context.glasses.capture.plan.PlanTestKit.trial
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanControllerTest {
    private var now = 1_000L
    private var boot = "bc:1"
    private val mem = MemPersistence()
    private val acks = Acks()
    private val host = FakeHost()
    private fun ctl(m: MemPersistence = mem, h: FakeHost? = host) =
        PlanController(m, { now }, { boot }, { ContextState.UNKNOWN }, { acks.sent += it }).also { it.host = h }

    // a plan whose gate differs from today's, so "applied" is observable
    private val tuned = PlanTestKit.plan(PlanTestKit.plan().config.let { it.copy(gate = it.gate.copy(hamming = 14)) })

    @Test fun effectiveOnlyAfterActivation() {
        val c = ctl(); host.quiet = false
        val a = c.onMessage(trial(1, tuned))
        assertEquals(PlanAckState.STAGED, a.state)
        assertEquals(listOf("STAGED"), acks.states())
        assertSame(ActiveConfig.SETTINGS, c.active)
        host.quiet = true; now += 1_000; c.tick(now)
        assertEquals(listOf("STAGED", "EFFECTIVE"), acks.states())
        assertEquals(GateParams(hamming = 14), c.active.gate)
    }

    @Test fun quietAtArrivalActivatesAtOnce() {
        val c = ctl()
        c.onMessage(trial(1, tuned))
        assertEquals(listOf("STAGED", "EFFECTIVE"), acks.states())
        assertEquals(1, host.installs.size)
    }

    @Test fun stagedActivationWaitsForQuiet() {
        val c = ctl(); host.quiet = false
        c.onMessage(trial(1, tuned))
        for (i in 1..9) { now += 1_000; c.tick(now) }          // 9 s staged
        assertTrue(host.installs.isEmpty()); assertEquals(0, host.aborts)
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun activationAbortsBoutAfter10s() {
        val c = ctl(); host.quiet = false
        c.onMessage(trial(1, tuned))
        now += 9_999; c.tick(now); assertEquals(0, host.aborts)
        now += 1; c.tick(now)
        assertEquals(1, host.aborts); assertEquals(1, host.installs.size)
        assertEquals(listOf("STAGED", "EFFECTIVE"), acks.states())
    }

    @Test fun noMixedOldDetectorNewCaps() {
        val c = ctl(); host.quiet = false
        val rest = PlanTestKit.withRow(tuned.config, com.rokid.context.capture.plan.PlanState.REST) { it.copy(capPerMin = 1) }
        c.onMessage(trial(1, PlanTestKit.plan(rest)))
        // staged but not activated: the gate values, the glance config and the rows are ALL still the old ones
        assertEquals(GateParams(), c.active.gate)
        assertEquals(2, c.active.contextCap(ContextState.REST))
        assertEquals(com.rokid.context.glasses.capture.motion.GlanceParams(), c.active.glance)
        assertTrue(host.installs.isEmpty())
        host.quiet = true; now += 250; c.tick(now)
        assertEquals(1, c.active.contextCap(ContextState.REST))
        assertSame(c.active, host.installs.single())
    }

    @Test fun shadowAndSettingsNeverChangeTheRealInputs() {
        val c = ctl()
        c.onMessage(shadow(1, tuned))
        assertSame(ActiveConfig.SETTINGS, c.active)
        assertEquals(listOf("STAGED", "EFFECTIVE"), acks.states())
        assertEquals(PlanMode.SHADOW, mem.state!!.mode)
        c.onMessage(settings(2))
        assertSame(ActiveConfig.SETTINGS, c.active)
    }

    @Test fun liveIsNeverApplied() {
        val c = ctl()
        val a = c.onMessage(PlanTestKit.msg(1, PlanMode.LIVE, tuned))
        assertEquals(PlanAckState.REJECTED, a.state); assertEquals("unsupported:live", a.reason)
        assertSame(ActiveConfig.SETTINGS, c.active); assertTrue(host.installs.isEmpty())
    }

    @Test fun aPersistedLiveStateIsRevertedOnLoadNotApplied() {
        val seed = MemPersistence()
        PlanReceiver(seed, { now }, { boot }).also { it.onMessage(trial(1, tuned)); it.activated(1) }
        seed.state = seed.state!!.copy(mode = PlanMode.LIVE, trialStartElapsed = null, trialMaxMs = null)
        val c = ctl(seed)
        assertSame(ActiveConfig.SETTINGS, c.active); assertTrue(host.installs.isEmpty())
        assertEquals(PlanMode.SETTINGS, seed.state!!.mode)
        assertEquals("unsupported:live", seed.state!!.terminal[1])
    }

    @Test fun trialEndsByItselfLocally() {
        val c = ctl()
        c.onMessage(trial(1, tuned, 60_000))
        val start = now
        now = start + 59_999; c.tick(now); assertEquals(GateParams(hamming = 14), c.active.gate)
        now = start + 60_000; c.tick(now)
        assertSame(ActiveConfig.SETTINGS, c.active)
        assertEquals("REVERTED:auto:trial_end", acks.states().last())
        assertSame(ActiveConfig.SETTINGS, host.installs.last())
    }

    @Test fun rebootEndsTheTrialBeforeAnythingIsApplied() {
        val c1 = ctl(); c1.onMessage(trial(1, tuned, 3_600_000))
        assertNotSame(ActiveConfig.SETTINGS, c1.active)
        boot = "bc:2"; now += 10_000
        val h2 = FakeHost(); acks.sent.clear()
        val c2 = ctl(mem, h2)
        assertSame(ActiveConfig.SETTINGS, c2.active)
        assertTrue(h2.installs.isEmpty(), "a persisted TRIAL of another boot must never be applied")
        assertEquals("auto:reboot", mem.state!!.terminal[1])
        val s = c2.statusAck()
        assertEquals(PlanAckState.REVERTED, s.state); assertEquals("auto:reboot", s.reason); assertEquals(1L, s.gen)
        assertEquals(PlanReceiver.CAPS_EXPR, s.caps)
    }

    @Test fun aPersistedTrialOfTheSameBootResumesWithItsOriginalClock() {
        val c1 = ctl(); c1.onMessage(trial(1, tuned, 60_000))
        val start = now
        now += 30_000
        val h2 = FakeHost(); val c2 = ctl(mem, h2)
        assertEquals(GateParams(hamming = 14), c2.active.gate)
        now = start + 60_000; c2.tick(now)
        assertSame(ActiveConfig.SETTINGS, c2.active)
    }

    @Test fun aStagedStateFromACrashMidApplyLeavesSettingsUntilActivated() {
        val seed = MemPersistence()
        PlanReceiver(seed, { now }, { boot }).onMessage(trial(1, tuned))     // staged, never activated
        val h = FakeHost(quiet = false); val c = ctl(seed, h)
        assertSame(ActiveConfig.SETTINGS, c.active)
        h.quiet = true; now += 250; c.tick(now)
        assertEquals(GateParams(hamming = 14), c.active.gate)
        assertTrue(seed.state!!.effective)
    }

    @Test fun aFailedCommitAtActivationRollsBackToSettings() {
        val c = ctl(); host.quiet = false
        c.onMessage(trial(1, tuned))
        host.quiet = true; mem.failNextCommit = true; now += 250
        c.tick(now)
        assertSame(ActiveConfig.SETTINGS, c.active)
        assertSame(ActiveConfig.SETTINGS, host.installs.last())
        assertTrue(acks.states().none { it == "EFFECTIVE" })
        now += 250; c.tick(now)                       // the next tick retries and succeeds
        assertEquals("EFFECTIVE", acks.states().last())
        assertEquals(GateParams(hamming = 14), c.active.gate)
    }

    @Test fun oldPhoneRevertsPersistedPlan() {
        val c = ctl(); c.onMessage(trial(1, tuned))
        c.onPhoneSettings(planAware = true); assertEquals(GateParams(hamming = 14), c.active.gate)
        c.onPhoneSettings(planAware = false)
        assertSame(ActiveConfig.SETTINGS, c.active)
        assertEquals("REVERTED:auto:old_phone", acks.states().last())
        val n = acks.sent.size
        c.onPhoneSettings(planAware = false)
        assertEquals(n, acks.sent.size)               // nothing left to revert
    }

    @Test fun noPlanAndOldPhoneIsSilent() {
        val c = ctl(); c.onPhoneSettings(planAware = false)
        assertTrue(acks.sent.isEmpty()); assertNull(mem.state)
    }

    @Test fun statusAckWithoutAnyPlanIsTheCapsAnnouncement() {
        val a = ctl().statusAck()
        assertEquals(0L, a.gen); assertEquals(PlanReceiver.CAPS_EXPR, a.caps); assertEquals("", a.phoneInstall)
    }

    @Test fun anAbsentEditableRowIsTheUnknownRowNotTheDefaultRestRow() {
        val cfg = tuned.config.copy(policy = tuned.config.policy - com.rokid.context.capture.plan.PlanState.REST)
        val a = ActiveConfig.of(PlanTestKit.plan(cfg))
        assertEquals(null, a.contextCap(ContextState.REST))
        assertEquals(0L, a.policyFor(ContextState.REST).minSettledCheckMs)
        assertEquals(6, a.table.size)
    }

    @Test fun receiverAccessIsSerialised() {
        val inside = AtomicInteger(); val overlap = AtomicInteger()
        val slow = object : MemPersistence() {
            override fun commit(s: com.rokid.context.capture.plan.ReceiverState) {
                if (inside.incrementAndGet() > 1) overlap.incrementAndGet()
                Thread.sleep(1); super.commit(s); inside.decrementAndGet()
            }
        }
        val c = ctl(slow, FakeHost())
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (0 until 4).map { t -> pool.submit { for (i in 1..40) { val g = (i * 4 + t).toLong(); c.onMessage(shadow(g)); c.tick(now + g); c.onPhoneSettings(true) } } }
        jobs.forEach { it.get() }; pool.shutdown()
        assertEquals(0, overlap.get(), "two threads were inside the receiver at once")
    }
}
