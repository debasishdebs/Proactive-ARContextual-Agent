package io.github.pca.nexusport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T6, the sink side: the four action-only skills and their gates, fail closed, 15 s ceiling, no content. */
class SkillsTest {
    private val g = FakeGates()
    private val p = FakeSkills()
    private var cfg = ON_SKILLS
    private val logs = mutableListOf<String>()
    private fun sink(timeout: Long = 12_000) = NexusHudSink(FakeNexusTransport(), g, { cfg }, FakeActions(), { 0L }, { logs += it }, null, p, timeout)
    private fun run(s: NexusHudSink, op: String) = s.events.invokeSkill(op)

    @Test fun offByDefault() {
        val s = NexusHudSink(FakeNexusTransport(), g, { NexusConfig() }, FakeActions(), { 0L }, {}, null, p)
        for (op in SkillOp.entries) assertEquals("fail:disabled", run(s, op.id))
        cfg = NexusConfig(enabled = true); assertEquals("fail:disabled", run(sink(), "status"))
        cfg = NexusConfig(skillsEnabled = true); assertEquals("fail:disabled", run(sink(), "status"))   // Nexus mode itself off
        assertTrue(p.calls.isEmpty())
        assertEquals(NexusFeatures.SKILLS, ON_SKILLS.featureMask())
    }

    @Test fun eachOperationAnswersWithAStateEnum() {
        val s = sink()
        p.result = { when (it) { SkillOp.PAUSE_CAPTURE -> SkillResult.Done("paused"); else -> SkillResult.Done("capturing") } }
        assertEquals("ok:capturing", run(s, "status")); assertEquals("ok:paused", run(s, "pause_capture"))
        assertEquals("ok:capturing", run(s, "resume_capture")); assertEquals("ok:capturing", run(s, "mark_moment"))
        assertEquals(SkillOp.entries.toList(), p.calls.toList())
        assertEquals("fail:unsupported_operation", run(s, "search_memory")); assertEquals("fail:unsupported_operation", run(s, ""))
    }

    @Test fun aZoneFenceIsAPauseZoneFailureOrAContentFreeStatus() {
        val s = sink()
        for (flip in listOf<(FakeGates) -> Unit>({ it.zone = true }, { it.ask = true })) {
            val gg = FakeGates(); flip(gg); val ss = NexusHudSink(FakeNexusTransport(), gg, { cfg }, FakeActions(), { 0L }, {}, null, p)
            assertEquals("fail:pause_zone", run(ss, "resume_capture")); assertEquals("fail:pause_zone", run(ss, "mark_moment"))
            assertEquals("ok:zone_paused", run(ss, "status")); assertEquals("ok:capturing", run(ss, "pause_capture"))
        }
        assertEquals(listOf(SkillOp.PAUSE_CAPTURE, SkillOp.PAUSE_CAPTURE), p.calls, "only the pause reached the phone")
        g.paused = true; assertEquals("fail:paused", run(s, "mark_moment"))
        g.paused = false; g.worn = false; assertEquals("fail:not_worn", run(s, "mark_moment"))
    }

    @Test fun aThrowingGateFailsClosed() {
        val s = sink()
        g.throwOn = "zone"
        assertEquals("fail:unavailable", run(s, "resume_capture")); assertEquals("fail:unavailable", run(s, "mark_moment")); assertEquals("fail:unavailable", run(s, "status"))
    }

    @Test fun theProvidersRefusalsPassAndUnknownCodesAreFlattened() {
        val s = sink()
        p.result = { SkillResult.Refused("pause_zone") }; assertEquals("fail:pause_zone", run(s, "mark_moment"))
        p.result = { SkillResult.Refused("home_address_is_12_Main_St") }; assertEquals("fail:failed", run(s, "mark_moment"))
        p.result = { SkillResult.Done("Charminar") }; assertEquals("fail:invalid_result", run(s, "status"))
        p.boom = true; assertEquals("fail:failed", run(s, "status"))
    }

    @Test fun aHungProviderAnswersInsideTheDeadline() {
        val s = sink(timeout = 150)
        p.hang = true
        val t0 = System.nanoTime()
        assertEquals("fail:deadline_exceeded", run(s, "resume_capture"))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000)
        assertTrue(NexusLimits.SKILL_HOST_TIMEOUT_MS < NexusLimits.SKILL_DEADLINE_MS)
    }

    @Test fun noProviderIsUnavailableAndTheWireIsStrict() {
        val s = NexusHudSink(FakeNexusTransport(), g, { cfg }, FakeActions(), { 0L }, {}, null, null)
        assertEquals("fail:unavailable", run(s, "status"))
        assertEquals(SkillResult.Done("paused"), SkillWire.decode("ok:paused"))
        for (bad in listOf("ok:", "ok:memory text", "fail:Home", "fail:", "OK:paused", "paused", "", "ok:paused\nsecret")) assertEquals(SkillResult.Refused(SkillCodes.INVALID_RESULT), SkillWire.decode(bad), bad)
        assertEquals(SkillResult.Refused(SkillCodes.INVALID_RESULT), SkillWire.decode(null))
    }

    @Test fun logsCarryOnlyTheOpAndAFlag() {
        val s = sink(); p.result = { SkillResult.Done("paused") }
        run(s, "status"); run(s, "mark_moment")
        assertEquals(listOf("nexus skill op=STATUS ok=true", "nexus skill op=MARK_MOMENT ok=true"), logs)
    }

    @Test fun theConnectedBridgeGetsTheSkillHostOnlyWhenAProviderExists() {
        val a = FakeNexusTransport(); NexusHudSink(a, g, { cfg }, FakeActions(), { 0L }, {}, null, p).onBridgeConnected()
        val b = FakeNexusTransport(); NexusHudSink(b, g, { cfg }, FakeActions(), { 0L }, {}, null, null).onBridgeConnected()
        assertTrue(a.skillHost); assertTrue(!b.skillHost)
    }
}
