package com.rokid.context.core.model

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 3a wire: additive channels and fields only, `PROTOCOL_VERSION` stays 1, old peers decode and ignore (S74). */
class WireSlice3aTest {
    private fun res(name: String): String = checkNotNull(javaClass.getResource("/compat/$name")) { name }.readText().trim()
    private inline fun <reified T> ok(text: String): T = assertIs<Decoded.Ok<T>>(Wire.decode<T>(text)).value

    /** A frozen copy of the v1 classes as an old peer has them (no slice 3a fields). */
    @Serializable private data class OldCommandEnvelope(val v: Int = PROTOCOL_VERSION, val command: Command)
    @Serializable private data class OldHudSettingsSubset(val v: Int = PROTOCOL_VERSION, val verbosity: Verbosity = Verbosity.MEDIUM, val scale: Float = 0.4f)

    @Test fun channelNamesAndVersion() {
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals("pc_capture_plan", Channels.CAPTURE_PLAN)
        assertEquals("pc_capture_plan_ack", Channels.CAPTURE_PLAN_ACK)
        assertEquals("pc_mark", Channels.MARK)
        assertEquals("pc_mark_ack", Channels.MARK_ACK)
        assertEquals("pc_trace_ctl", Channels.TRACE_CTL)
        assertEquals("pc_trace_chunk", Channels.TRACE_CHUNK)
        assertEquals("pc_trace_ack", Channels.TRACE_ACK)
    }

    @Test fun capturePlanMessageRoundTripAndDefaults() {
        val m = CapturePlanMessage(gen = 7, publishId = "p1", mode = PlanMode.TRIAL, configHash = "abcdef012345", planJson = "{}", trialMaxMs = 60_000, phoneInstall = "inst-1", atMs = 5, sessionStartMs = 3)
        assertEquals(m, ok<CapturePlanMessage>(Wire.encode(m)))
        val d = ok<CapturePlanMessage>("""{"v":1,"gen":2}""")
        assertEquals(PlanMode.SETTINGS, d.mode); assertEquals("", d.phoneInstall); assertNull(d.trialMaxMs); assertEquals(0L, d.sessionStartMs)
        assertEquals(PlanMode.SETTINGS, ok<CapturePlanMessage>("""{"v":1,"gen":2,"mode":"HYPER"}""").mode)
    }

    @Test fun capturePlanAckRoundTripAndDefaults() {
        val a = CapturePlanAck(gen = 3, mode = PlanMode.SHADOW, state = PlanAckState.EFFECTIVE, effectiveHash = "abcdef012345", caps = listOf("plan1"), phoneInstall = "i", atMs = 9, sessionStartMs = 4)
        assertEquals(a, ok<CapturePlanAck>(Wire.encode(a)))
        val d = ok<CapturePlanAck>("""{"v":1,"gen":0}""")
        assertEquals(PlanAckState.REJECTED, d.state); assertEquals(1, d.planSchemaMax); assertEquals(emptyList(), d.caps); assertEquals("", d.phoneInstall)
        assertEquals(PlanAckState.REJECTED, ok<CapturePlanAck>("""{"v":1,"gen":1,"state":"MAYBE"}""").state)
    }

    @Test fun markMessagesRoundTripAndDefaults() {
        val m = MarkMessage(epoch = "e1", seq = 4, atMs = 10, atElapsedMs = 20, bootCount = 3, sessionStartMs = 5, listen = true, listenId = "L1", undo = 2, deletionCounter = 6)
        assertEquals(m, ok<MarkMessage>(Wire.encode(m)))
        val d = ok<MarkMessage>("""{"v":1,"epoch":"e","seq":1,"atMs":1,"atElapsedMs":2}""")
        assertEquals(-1, d.bootCount); assertEquals("tap", d.src); assertFalse(d.listen); assertNull(d.listenId); assertNull(d.undo); assertEquals(-1L, d.deletionCounter)
        val ack = MarkAck(epoch = "e", seqs = listOf(1, 2))
        assertEquals(ack, ok<MarkAck>(Wire.encode(ack)))
    }

    @Test fun traceMessagesRoundTripAndDefaults() {
        val c = TraceControl(collect = true, collectUntilMs = 99, installId = "i", deletionCounter = 2, tombstones = listOf("t-1"), forgetFromMs = 1, forgetToMs = 2, send = true, atMs = 5, sessionStartMs = 3)
        assertEquals(c, ok<TraceControl>(Wire.encode(c)))
        val d = ok<TraceControl>("""{"v":1}""")
        assertFalse(d.collect); assertFalse(d.send); assertEquals("", d.installId); assertEquals(0L, d.deletionCounter); assertEquals(emptyList(), d.tombstones)
        val ch = TraceChunk(traceId = "trace-0001", kind = "imu", seq = 1, count = 3, installId = "i", deletionCounter = 1, sha256 = "00", b64 = "AA==", atMs = 1, sessionStartMs = 1)
        assertEquals(ch, ok<TraceChunk>(Wire.encode(ch)))
        val a = TraceAck(traceId = "trace-0001", ok = true, atMs = 3, sessionStartMs = 2)
        assertEquals(a, ok<TraceAck>(Wire.encode(a)))
        assertEquals(0L, ok<TraceAck>("""{"v":1,"traceId":"x","ok":false}""").sessionStartMs)
    }

    @Test fun anotherVersionIsAMismatchNotAnApply() {
        assertIs<Decoded.VersionMismatch>(Wire.decode<CapturePlanMessage>("""{"v":2,"gen":1}"""))
        assertIs<Decoded.VersionMismatch>(Wire.decode<MarkMessage>("""{"v":2,"epoch":"e","seq":1,"atMs":1,"atElapsedMs":1}"""))
    }

    @Test fun oldPhoneHudSettingsDecodesPlanAwareFalse() {
        val old = ok<HudSettings>(res("old_hud_settings.json"))
        assertFalse(old.planAware)
        assertEquals(CaptureMode.SMART, old.captureMode)
        assertTrue(ok<HudSettings>(Wire.encode(HudSettings(planAware = true))).planAware)
    }

    @Test fun oldCommandEnvelopeDecodesListenIdNull() {
        val env = ok<CommandEnvelope>(res("old_command_envelope.json"))
        assertNull(env.listenId)
        assertEquals(Command.Confirm("p-42", true), env.command)
        assertEquals("L9", ok<CommandEnvelope>(Wire.encode(CommandEnvelope(command = Command.ListenStart, listenId = "L9"))).listenId)
    }

    @Test fun oldDecoderIgnoresListenId() {
        val text = Wire.encode(CommandEnvelope(command = Command.ListenStart, listenId = "L9"))
        val old = Wire.json.decodeFromString<OldCommandEnvelope>(text)
        assertEquals(Command.ListenStart, old.command)
        val s = Wire.json.decodeFromString<OldHudSettingsSubset>(Wire.encode(HudSettings(planAware = true)))
        assertEquals(Verbosity.MEDIUM, s.verbosity)
    }

    @Test fun oldHelloStillDecodes() {
        assertEquals(71, ok<Hello>(res("old_glasses_hello.json")).glassesBatteryPct)
    }
}
