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
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>(Wire.encode(frame(null)).replace("\"seq\":7", "\"seq\":\"x\"")))
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

    /** Fix wave H4: the Rokid-closed counters are additive on the hello; an old hello reads 0 / null. */
    @Test fun helloRokidClosedCountersAreAdditive() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[]}"""
        val d = assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value
        assertEquals(0, d.rokidCloses); assertEquals(null, d.lastRestartGapSec)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList(), rokidCloses = 3, lastRestartGapSec = 38)
        assertEquals(Decoded.Ok(h), Wire.decode<Hello>(Wire.encode(h)))
    }

    /** Fix wave H3: `captureCue` is additive, default on; old phones' settings (no field) keep the blink on. */
    @Test fun captureCueIsAdditiveDefaultOn() {
        assertTrue(assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value.captureCue)
        val s = HudSettings(captureCue = false)
        assertEquals(Decoded.Ok(s), Wire.decode<HudSettings>(Wire.encode(s)))
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

    /** Phase 6: additive, so an older phone's settings decode on newer glasses and the other way round. */
    @Test fun askBlockedReasonIsAdditive() {
        val old = """{"v":$PROTOCOL_VERSION,"verbosity":"MEDIUM"}"""
        assertEquals(null, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(old)).value.askBlockedReason)
        val s = HudSettings(askBlockedReason = "Voice not supported on this phone")
        assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
    }

    /** Both builds decode with `ignoreUnknownKeys`: a field a newer build adds never breaks an older one. */
    @Test fun hudSettingsToleratesUnknownKeys() {
        val newer = """{"v":1,"askBlockedReason":"x","futureField":1}"""
        assertEquals(HudSettings(askBlockedReason = "x"), assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(newer)).value)
    }

    /** Phase 7 (S0.1): additive pause fields; an older payload decodes with defaults. */
    @Test fun newHudSettingsFieldsDefaultAndRoundTrip() {
        val old = """{"v":$PROTOCOL_VERSION,"verbosity":"MEDIUM","pauses":[]}"""
        val d = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(old)).value
        assertEquals(emptyList(), d.resumedPauseIds); assertFalse(d.pausesExact); assertEquals(null, d.zoneId)
        val s = HudSettings(resumedPauseIds = listOf("p1", "p2"), pausesExact = true, zoneId = "Asia/Kolkata")
        assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
    }

    /** Spec 2026-10-14 §3/§4, additive under v1: an older phone's settings decode with the icon and pop-up defaults. */
    @Test fun oldJsonDecodesWithIconAndPopupDefaults() {
        val old = """{"v":$PROTOCOL_VERSION,"verbosity":"MEDIUM","pauses":[]}"""
        val d = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(old)).value
        assertEquals(IconCorner.TOP_RIGHT, d.iconCorner)
        assertEquals(6, d.popupSeconds)
    }

    @Test fun iconAndPopupRoundTrip() {
        for (c in IconCorner.entries) {
            val s = HudSettings(iconCorner = c, popupSeconds = 12)
            assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
        }
        assertEquals(listOf("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"), IconCorner.entries.map { it.name })
    }

    @Test fun popupSecondsClamped() {
        assertEquals(3, clampPopupSeconds(0)); assertEquals(3, clampPopupSeconds(-5)); assertEquals(20, clampPopupSeconds(99))
        assertEquals(6, clampPopupSeconds(6))
        // A value outside the range on the wire (an older or buggy phone) is clamped where it is used.
        val wild = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1,"popupSeconds":900}""")).value
        assertEquals(20_000L, wild.popupMs())
        assertEquals(3_000L, HudSettings(popupSeconds = 1).popupMs())
    }

    @Test fun undoCommandAndUndoToastRoundTrip() {
        val env = CommandEnvelope(command = Command.Undo("p1"))
        assertEquals(Decoded.Ok(env), Wire.decode<CommandEnvelope>(Wire.encode(env)))
        val f = frame(Overlay.Toast("x", "p1"))
        assertEquals(Decoded.Ok(f), Wire.decode<StateFrame>(Wire.encode(f)))
        assertEquals(null, Overlay.Toast("x").undoProposalId)
    }

    /** BUG-R2-5 (additive): an Answer's undo round-trips, and an Answer without the field still decodes. */
    @Test fun answerUndoIsAdditive() {
        val f = frame(Overlay.Answer("q", "a", speaking = false, undoProposalId = "p1"))
        assertEquals(Decoded.Ok(f), Wire.decode<StateFrame>(Wire.encode(f)))
        val plain = frame(Overlay.Answer("q", "a", speaking = false))
        assertFalse(Wire.encode(plain).contains("undoProposalId"))
        assertEquals(Decoded.Ok(plain), Wire.decode<StateFrame>(Wire.encode(plain)))
    }

    private val oldFrameJson = """{"v":1,"frameId":"f1","chunkIdx":0,"chunkCount":1,"crc32":5,"capturedAtMs":9}"""

    @Test fun oldFrameMetaParsesWithDefaults() {
        val m = assertIs<Decoded.Ok<FrameMeta>>(Wire.decode<FrameMeta>(oldFrameJson)).value
        assertEquals(MotionState.UNKNOWN, m.motion)
        assertFalse(m.dwell)
        assertEquals(null, m.blur); assertEquals(null, m.dhash); assertEquals(null, m.width)
        assertEquals(null, m.sampleSeq); assertEquals(null, m.sessionId)
    }

    @Test fun oldHudSettingsMeanFixedMode() {
        val s = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value
        assertEquals(CaptureMode.FIXED, s.captureMode)
        assertTrue(s.pauseWhenNotWorn); assertTrue(s.captureCue)
        assertEquals(45, s.workMinutes); assertEquals(15, s.restMinutes); assertEquals(1000, s.quickLookMs)
    }

    @Test fun newFieldsRoundTrip() {
        val m = FrameMeta(frameId = "f", chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = 2, phash = "0123456789abcdef",
            motion = MotionState.WALKING, dwell = true, blur = 12.5f, dhash = "fedcba9876543210", width = 640, sampleSeq = 7L, sessionId = "s1")
        assertEquals(Decoded.Ok(m), Wire.decode<FrameMeta>(Wire.encode(m)))
        val s = HudSettings(captureMode = CaptureMode.SMART, pauseWhenNotWorn = false, workMinutes = 60, quickLookMs = 2000, restMinutes = 20)
        assertEquals(Decoded.Ok(s), Wire.decode<HudSettings>(Wire.encode(s)))
        val h = MotionHint(MotionState.VEHICLE, 99)
        assertEquals(h, Wire.json.decodeFromString<MotionHint>("""{"state":"VEHICLE","atMs":99}"""))
        assertEquals("pc_motion_hint", Channels.MOTION_HINT)
    }

    @Test fun unknownEnumValueFallsBackToUnknown() {
        val m = assertIs<Decoded.Ok<FrameMeta>>(Wire.decode<FrameMeta>("""{"v":1,"frameId":"f1","chunkIdx":0,"chunkCount":1,"crc32":5,"capturedAtMs":9,"motion":"FLYING"}""")).value
        assertEquals(MotionState.UNKNOWN, m.motion)
        val s = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1,"captureMode":"TELEPATHY"}""")).value
        assertEquals(CaptureMode.FIXED, s.captureMode)
    }

    @Test fun normalizedSnapsAndEnforcesRestAfterLongWork() {
        assertEquals(HudSettings(), HudSettings().normalized())
        val n = HudSettings(workMinutes = 77, restMinutes = 5, quickLookMs = 1600).normalized()
        assertEquals(75, n.workMinutes); assertEquals(15, n.restMinutes); assertEquals(1500, n.quickLookMs)
        assertEquals(5, HudSettings(workMinutes = 45, restMinutes = 5).normalized().restMinutes)
        assertEquals(20, HudSettings(workMinutes = 90, restMinutes = 20).normalized().restMinutes)
        assertEquals(30, HudSettings(workMinutes = 0).normalized().workMinutes)
        assertEquals(10, HudSettings(restMinutes = 11).normalized().restMinutes)
    }

    // Pins Wire.json coerceInputValues = true (global effects, see review A item 5).
    @Test fun coerceInputValuesFallsBackToDefaultsOnly() {
        fun settings(extra: String) = Wire.decode<HudSettings>("""{"v":$PROTOCOL_VERSION,$extra}""")
        // unknown enum strings on defaulted fields become the default instead of rejecting the whole message
        assertEquals(Decoded.Ok(HudSettings()), settings(""""verbosity":"LOUD","position":"NOWHERE","iconCorner":"MIDDLE""""))
        assertEquals(Verbosity.MEDIUM, (settings(""""verbosity":"LOUD"""") as Decoded.Ok).value.verbosity)
        assertEquals(HudPosition.BOTTOM_CENTER, (settings(""""position":"NOWHERE"""") as Decoded.Ok).value.position)
        assertEquals(IconCorner.TOP_RIGHT, (settings(""""iconCorner":"MIDDLE"""") as Decoded.Ok).value.iconCorner)
        // an explicit null for a defaulted non-null field takes the default
        assertEquals(Decoded.Ok(HudSettings()), settings(""""verbosity":null,"scale":null,"popupSeconds":null,"workMinutes":null"""))
        // a valid value still wins
        assertEquals(Verbosity.MAX, (settings(""""verbosity":"MAX"""") as Decoded.Ok).value.verbosity)
        // type errors are still Malformed
        assertIs<Decoded.Malformed>(settings(""""popupSeconds":"x""""))
        assertIs<Decoded.Malformed>(Wire.decode<StateFrame>(Wire.encode(frame(null)).replace("\"seq\":7", "\"seq\":\"x\"")))
        // enums without a default stay Malformed
        assertIs<Decoded.Malformed>(Wire.decode<CommandEnvelope>(
            """{"v":$PROTOCOL_VERSION,"command":{"kind":"page_changed","page":"NOPE"}}""",
        ))
        assertIs<Decoded.Malformed>(Wire.decode<CommandEnvelope>(
            """{"v":$PROTOCOL_VERSION,"command":{"kind":"pause","pause":{"pauseId":"p","origin":"NOPE","createdAtMs":1}}}""",
        ))
    }
}
