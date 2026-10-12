package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolTest {
    private fun frame(overlay: Overlay?) = StateFrame(
        seq = 7,
        capture = CaptureState.Paused(listOf(PauseRecord("p1", PauseOrigin.GLASSES, createdAtMs = 10, untilMs = null))),
        nextCaptureAtMs = 1_000,
        intervalSec = 300,
        glassesBatteryPct = 82,
        phoneLinked = true,
        aiHealth = Health.Degraded("AI slow"),
        queueDepth = 2,
        latestNudge = NudgeLine("Milk almost empty", 99),
        today = listOf(MemoryLine("m1", 5, "Keys on hallway shelf")),
        overlay = overlay,
    )

    @Test fun stateFrameRoundTripsWithEveryOverlay() {
        val overlays = listOf(
            null, Overlay.Listening("what do"), Overlay.Answer("q?", "Milk and eggs", speaking = true), Overlay.Nudge("Heads up"),
            Overlay.Confirm("prop-1", "Add milk?", "Shopping list"), Overlay.PausedSplash, Overlay.Toast("Saved"),
        )
        for (o in overlays) {
            val decoded = Wire.decode<StateFrame>(Wire.encode(frame(o)))
            assertEquals(Decoded.Ok(frame(o)), decoded)
        }
    }

    @Test fun everyCommandRoundTripsInAnEnvelope() {
        val pause = PauseRecord("p2", PauseOrigin.PHONE, 1, 2)
        val commands = listOf(
            Command.Tap, Command.DoubleTap, Command.Swipe(forward = true, long = false), Command.Confirm("prop-1", yes = true),
            Command.Pause(pause), Command.Resume(setOf("p1", "p2")), Command.PageChanged(HudPage.TODAY), Command.ListenStart, Command.Cancel,
        )
        for (c in commands) assertEquals(Decoded.Ok(CommandEnvelope(command = c)), Wire.decode<CommandEnvelope>(Wire.encode(CommandEnvelope(command = c))))
    }

    @Test fun otherVersionIsAMismatchNotAnError() {
        val text = Wire.encode(frame(null)).replace("\"v\":$PROTOCOL_VERSION", "\"v\":${PROTOCOL_VERSION + 1}")
        assertEquals(Decoded.VersionMismatch(PROTOCOL_VERSION + 1), Wire.decode<StateFrame>(text))
    }

    @Test fun missingVersionIsAMismatch() {
        assertEquals(Decoded.VersionMismatch(null), Wire.decode<FrameAck>("""{"frameId":"f","ok":true}"""))
    }

    @Test fun unknownFieldsAreIgnored() {
        val text = Wire.encode(FrameAck(frameId = "f1", ok = true)).replace("{", """{"future":42,""")
        assertEquals(Decoded.Ok(FrameAck(frameId = "f1", ok = true)), Wire.decode<FrameAck>(text))
    }

    @Test fun malformedTextIsReported() {
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>("not json"))
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>("""{"v":$PROTOCOL_VERSION,"seq":"x"}"""))
    }

    /** Privacy: a decode error must never quote the payload (it can hold `today` memory text), only a class name. */
    @Test fun malformedReasonNeverQuotesThePayload() {
        val marker = "SECRET_MEMORY_MARKER"
        val bad = """{"v":$PROTOCOL_VERSION,"seq":1,"capture":{"kind":"running"},"intervalSec":300,"phoneLinked":true,""" +
            """"aiHealth":{"kind":"ok"},"today":[{"id":"m1","atMs":"$marker","text":"$marker"}]}"""
        val d = assertIs<Decoded.Malformed>(Wire.decode<StateFrame>(bad))
        assertFalse(d.reason.contains(marker), d.reason)
        assertFalse(d.reason.contains("today"), d.reason)
    }

    /** `resumedAtMs` is additive: a v1 hello without it still decodes (no PROTOCOL_VERSION bump). */
    @Test fun helloWithoutResumedStillDecodes() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[]}"""
        assertEquals(emptyMap<String, Long>(), assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value.resumedAtMs)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList(), resumedAtMs = mapOf("p1" to 4L))
        assertEquals(Decoded.Ok(h), Wire.decode<Hello>(Wire.encode(h)))
    }

    @Test fun quietHoursWrapPastMidnight() {
        val q = QuietHours(startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(q.contains(23 * 60)); assertTrue(q.contains(0)); assertTrue(q.contains(6 * 60 + 59))
        assertFalse(q.contains(7 * 60)); assertFalse(q.contains(12 * 60)); assertTrue(q.contains(22 * 60))
    }

    @Test fun quietHoursSameDayAndEmpty() {
        val q = QuietHours(9 * 60, 17 * 60)
        assertTrue(q.contains(9 * 60)); assertFalse(q.contains(17 * 60)); assertFalse(q.contains(8 * 60))
        assertFalse(QuietHours(600, 600).contains(600), "start == end means no quiet hours")
    }

    @Test fun pauseRecordActivity() {
        assertTrue(PauseRecord("a", PauseOrigin.PHONE, 0, null).isActiveAt(Long.MAX_VALUE))
        assertTrue(PauseRecord("a", PauseOrigin.PHONE, 0, 100).isActiveAt(99))
        assertFalse(PauseRecord("a", PauseOrigin.PHONE, 0, 100).isActiveAt(100))
    }

    @Test fun defaultGesturesMatchSpec() {
        assertEquals(GestureAction.ASK, GestureDefaults.map[Gesture.TAP])
        assertEquals(GestureAction.PAUSE_RESUME, GestureDefaults.map[Gesture.DOUBLE_TAP])
        assertEquals(GestureAction.NEXT_PAGE, GestureDefaults.map[Gesture.SWIPE_FORWARD])
        assertEquals(GestureAction.PREV_PAGE, GestureDefaults.map[Gesture.SWIPE_BACK])
        assertEquals(HudSettings().gestures, GestureDefaults.map)
    }

    @Test fun hudSettingsDefaults() {
        val s = HudSettings()
        assertEquals(Verbosity.MEDIUM, s.verbosity); assertEquals(0.4f, s.scale); assertEquals(HudPosition.BOTTOM_CENTER, s.position)
        assertEquals(Decoded.Ok(s), Wire.decode<HudSettings>(Wire.encode(s)))
    }
}
