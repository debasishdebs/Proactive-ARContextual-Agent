package com.rokid.context.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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

    /** Wave 3: the unverified and other force-stop counts are additive on the hello. */
    @Test fun helloUnverifiedAndOtherClosesAreAdditive() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[]}"""
        val d = assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value
        assertEquals(0, d.unverifiedCloses); assertEquals(0, d.otherCloses)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList(), rokidCloses = 3, unverifiedCloses = 2, otherCloses = 4)
        assertEquals(Decoded.Ok(h), Wire.decode<Hello>(Wire.encode(h)))
    }

    @Test fun closesSummaryWording() {
        assertEquals(null, closesSummary(0, null, 0, 0))
        assertEquals("Rokid closed the app 1 time (last gap 38s)", closesSummary(1, 38, 0, 0))
        assertEquals("Rokid closed the app 5 times (last gap 38s; 2 unverified; 1 other force-stop)", closesSummary(5, 38, 2, 1))
        assertEquals("Rokid closed the app 0 times (3 other force-stops)", closesSummary(0, null, 0, 3))
    }

    /** Fix wave I: the exit stamp, icon-blocked flag, wear session start and `pc_auto_open` are additive. */
    @Test fun waveIFieldsAreAdditive() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[]}"""
        val d = assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value
        assertEquals(null, d.userExitAtMs); assertFalse(d.iconBlocked)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList(), userExitAtMs = 9, iconBlocked = true)
        assertEquals(Decoded.Ok(h), Wire.decode<Hello>(Wire.encode(h)))
        assertEquals(0L, assertIs<Decoded.Ok<WearMessage>>(Wire.decode<WearMessage>("""{"v":$PROTOCOL_VERSION,"worn":false,"atMs":5}""")).value.sessionStartMs)
        val a = AutoOpen(atMs = 7)
        assertEquals(Decoded.Ok(a), Wire.decode<AutoOpen>(Wire.encode(a)))
    }

    /** LGC6-min: `diagFlagState` and `traceBlocker` are additive strings (fixed vocabulary, never free text); PROTOCOL_VERSION stays 1. */
    @Test fun helloDiagFieldsRoundTripAndOldPeersStillParse() {
        assertEquals(1, PROTOCOL_VERSION)
        val old = """{"v":1,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[]}"""
        val d = assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value
        assertEquals(null, d.diagFlagState); assertEquals(null, d.traceBlocker)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList(), diagFlagState = "armed_no_epoch", traceBlocker = "gate_off")
        assertEquals(Decoded.Ok(h), Wire.decode<Hello>(Wire.encode(h)))
        // a newer peer's unknown extra field is tolerated by the existing parser rules
        val newer = """{"v":1,"glassesBatteryPct":80,"bufferSize":0,"glassesNowMs":5,"pauses":[],"diagFlagState":"enabled","traceBlocker":"none","futureField":{"x":1}}"""
        val n = assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(newer)).value
        assertEquals("enabled", n.diagFlagState); assertEquals("none", n.traceBlocker)
        // an all-default hello adds no new key the old phone would have to know (explicitNulls/defaults follow the codec's own rules)
        assertEquals(Wire.encode(Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 5, pauses = emptyList())).contains("diagFlagState"), false)
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

    @Test fun oldSettingsJsonDecodesToBatteryDefaults() {
        val d = Wire.decode<HudSettings>("""{"v":$PROTOCOL_VERSION}""")
        val s = (d as Decoded.Ok).value
        assertEquals(CameraWake.SETTLE, s.cameraWake)
        assertEquals(0, s.batteryTargetHours)
        assertFalse(s.pauseWhenPhoneInUse)
        assertEquals(0, HudSettings().batteryTargetHours)
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test fun unknownCameraWakeDecodesToSettle() {
        val d = Wire.decode<HudSettings>("""{"v":$PROTOCOL_VERSION,"cameraWake":"WARP"}""")
        assertEquals(CameraWake.SETTLE, (d as Decoded.Ok).value.cameraWake)
    }

    @Test fun phoneContextMessageRoundTrips() {
        val m = PhoneContextMessage(inUse = true, atMs = 123L, sessionStartMs = 99L)
        assertEquals(Decoded.Ok(m), Wire.decode<PhoneContextMessage>(Wire.encode(m)))
        assertEquals(0L, PhoneContextMessage(inUse = false, atMs = 1L).sessionStartMs)
        assertEquals("pc_phone_ctx", Channels.PHONE_CONTEXT)
    }

    @Test fun batteryTargetHoursSnapsToAllowedSet() {
        fun snap(h: Int) = HudSettings(batteryTargetHours = h).normalized().batteryTargetHours
        assertEquals(0, snap(1))
        assertEquals(3, snap(2))
        assertEquals(6, snap(9))
        assertEquals(0, snap(0))
        assertEquals(5, snap(5))
    }

    @Test fun protocolVersionStaysOne() = assertEquals(1, PROTOCOL_VERSION)

    @Test fun waveA3SettingsDefaultsAndRoundTrip() {
        val old = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value
        assertFalse(old.workRestEnabled); assertEquals(MotionLabel.NONE, old.motionLabel); assertEquals(emptyList(), old.motionSourcesOff)
        val unknown = assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1,"motionLabel":"DANCING"}""")).value
        assertEquals(MotionLabel.NONE, unknown.motionLabel)
        val s = HudSettings(workRestEnabled = true, motionLabel = MotionLabel.EATING, motionSourcesOff = listOf("phone_motion"))
        assertEquals(s, assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>(Wire.encode(s))).value)
        assertEquals(listOf("phone_motion"), HudSettings(motionSourcesOff = listOf("glasses_imu", "phone_motion")).normalized().motionSourcesOff)
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test fun waveBMotionContextMessage() {
        assertEquals("pc_motion_ctx", Channels.MOTION_CONTEXT)
        assertEquals(1, PROTOCOL_VERSION)
        val m = com.rokid.context.core.model.context.MotionContextMessage(
            state = com.rokid.context.core.model.context.ContextState.VEHICLE_DRIVING, confidence = 0.8f, sinceMs = 1, atMs = 9,
            validForMs = 1000, apply = false, reasons = listOf("SPEED"), sessionStartMs = 3,
        )
        assertEquals(m, assertIs<Decoded.Ok<com.rokid.context.core.model.context.MotionContextMessage>>(Wire.decode<com.rokid.context.core.model.context.MotionContextMessage>(Wire.encode(m))).value)
        val old = assertIs<Decoded.Ok<com.rokid.context.core.model.context.MotionContextMessage>>(Wire.decode<com.rokid.context.core.model.context.MotionContextMessage>("""{"v":1,"atMs":5}""")).value
        assertEquals(com.rokid.context.core.model.context.ContextState.UNKNOWN, old.state)
        assertEquals(180_000L, old.validForMs); assertTrue(old.apply); assertEquals(emptyList(), old.reasons)
        val tele = assertIs<Decoded.Ok<com.rokid.context.core.model.context.MotionContextMessage>>(Wire.decode<com.rokid.context.core.model.context.MotionContextMessage>("""{"v":1,"atMs":5,"state":"TELEPORTING"}""")).value
        assertEquals(com.rokid.context.core.model.context.ContextState.UNKNOWN, tele.state)
        val r = assertIs<Decoded.Ok<com.rokid.context.core.model.context.MotionContextMessage>>(Wire.decode<com.rokid.context.core.model.context.MotionContextMessage>("""{"v":1,"atMs":5,"reasons":["SPEED","NEW_REASON"]}""")).value
        assertEquals(listOf("SPEED", "NEW_REASON"), r.reasons)
    }

    @Test fun waveBMotionHint() {
        val h = MotionHint(MotionState.WALKING, 3)
        assertTrue(Wire.encode(h).contains("\"v\":1"))
        assertEquals(h, assertIs<Decoded.Ok<MotionHint>>(Wire.decode<MotionHint>(Wire.encode(h))).value)
        val old = assertIs<Decoded.Ok<MotionHint>>(Wire.decode<MotionHint>("""{"state":"STILL","atMs":3,"v":1}""")).value
        assertEquals(-1f, old.stillFrac5m); assertEquals(MotionState.STILL, old.state)
    }

    // Place Guide PG3: the card rides an optional field of the existing nudge overlay; PROTOCOL_VERSION stays 1.
    @Serializable private data class LegacyNudge(val text: String)

    @Test fun placeCardRoundTrips() {
        val o = Overlay.Nudge("Charminar: A monument.", PlaceCardInfo("Charminar", "A monument.", "Wikipedia \u00b7 fetched 12 Nov", nearby = false))
        val f = frame(o)
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals(f, assertIs<Decoded.Ok<StateFrame>>(Wire.decode<StateFrame>(Wire.encode(f))).value)
        val n = Overlay.Nudge("Mecca Masjid: 40 m away", PlaceCardInfo("Mecca Masjid", "40 m away", "nearby \u00b7 not checked", nearby = true))
        assertEquals(n, assertIs<Decoded.Ok<StateFrame>>(Wire.decode<StateFrame>(Wire.encode(frame(n)))).value.overlay)
    }

    @Test fun anOldGlassesBuildIgnoresThePlaceField() {
        val json = Wire.encode<Overlay>(Overlay.Nudge("Charminar: A monument.", PlaceCardInfo("Charminar", "A monument.", "Wikipedia", false)))
        assertTrue(json.contains("\"place\""))
        // an older build's Nudge had only `text`: with ignoreUnknownKeys the extra field never breaks it and it shows the ordinary nudge text
        assertEquals("Charminar: A monument.", Wire.json.decodeFromString<LegacyNudge>(json).text)
    }

    // B2: the card side is an additive optional field of PlaceCardInfo; PROTOCOL_VERSION stays 1.
    @Serializable private data class OldPlaceCard(val title: String, val line: String, val source: String, val nearby: Boolean = false, val id: String? = null)
    @Serializable private data class OldPlaceNudge(val text: String, val place: OldPlaceCard? = null)

    @Test fun placeSideRoundTripsAndCentreSendsNothing() {
        assertEquals(1, PROTOCOL_VERSION)
        for (side in PlaceSide.entries) {
            val o = Overlay.Nudge("A: b", PlaceCardInfo("A", "b", "s", id = "c1", side = PlaceSide.toWire(side)))
            val back = assertIs<Decoded.Ok<StateFrame>>(Wire.decode<StateFrame>(Wire.encode(frame(o)))).value.overlay as Overlay.Nudge
            assertEquals(side, PlaceSide.parse(back.place!!.side))
        }
        val centre = Wire.encode<Overlay>(Overlay.Nudge("A: b", PlaceCardInfo("A", "b", "s", id = "c1", side = PlaceSide.toWire(PlaceSide.CENTRE))))
        assertFalse(centre.contains("side")) // byte-for-byte the pre-B2 frame
        assertEquals(Wire.encode<Overlay>(Overlay.Nudge("A: b", PlaceCardInfo("A", "b", "s", id = "c1"))), centre)
        assertTrue(Wire.encode<Overlay>(Overlay.Nudge("A: b", PlaceCardInfo("A", "b", "s", side = "LEFT"))).contains("\"side\":\"LEFT\""))
    }

    @Test fun anOldGlassesDecoderIgnoresSideAndANewOneDefaultsToCentre() {
        val withSide = Wire.encode<Overlay>(Overlay.Nudge("A: b", PlaceCardInfo("A", "b", "s", id = "c1", side = "RIGHT")))
        val old = Wire.json.decodeFromString<OldPlaceNudge>(withSide) // an older build's PlaceCardInfo has no `side`
        assertEquals("c1", old.place!!.id); assertEquals("A: b", old.text)
        val absent = Wire.json.decodeFromString<Overlay>("""{"kind":"nudge","text":"A: b","place":{"title":"A","line":"b","source":"s"}}""") as Overlay.Nudge
        assertEquals(null, absent.place!!.side); assertEquals(PlaceSide.CENTRE, PlaceSide.parse(absent.place!!.side))
        assertEquals(PlaceSide.CENTRE, PlaceSide.parse("UP")); assertEquals(PlaceSide.CENTRE, PlaceSide.parse(""))
    }

    @Test fun anOldPhoneNudgeHasNoPlace() {
        val n = assertIs<Overlay.Nudge>(Wire.json.decodeFromString<Overlay>("""{"kind":"nudge","text":"Leave in 10 min"}"""))
        assertEquals(null, n.place); assertEquals(Overlay.Nudge("Leave in 10 min"), n)
        assertFalse(Wire.encode<Overlay>(n).contains("place"))
    }

    @Test fun dismissPlaceRoundTripsAndAnUnknownCommandIsMalformedNotAThrow() {
        val e = CommandEnvelope(command = Command.DismissPlace("Charminar"))
        assertEquals(e, assertIs<Decoded.Ok<CommandEnvelope>>(Wire.decode<CommandEnvelope>(Wire.encode(e))).value)
        assertIs<Decoded.Malformed>(Wire.decode<CommandEnvelope>("""{"v":1,"command":{"kind":"from_the_future"}}"""))
    }

    // Codex r4: the card instance id is additive and optional in both directions.
    @Serializable private data class LegacyDismiss(val title: String)

    @Test fun theCardIdRoundTripsAndOldPeersInteroperate() {
        val o = Overlay.Nudge("Charminar: A.", PlaceCardInfo("Charminar", "A.", "Wikipedia", id = "pc7"))
        assertEquals(o, assertIs<Decoded.Ok<StateFrame>>(Wire.decode<StateFrame>(Wire.encode(frame(o)))).value.overlay)
        val e = CommandEnvelope(command = Command.DismissPlace("Charminar", "pc7"))
        assertEquals(e, assertIs<Decoded.Ok<CommandEnvelope>>(Wire.decode<CommandEnvelope>(Wire.encode(e))).value)
        // an older glasses build (no id) sends the title only: it decodes with cardId = null (the phone then falls back to the title)
        val old = assertIs<Decoded.Ok<CommandEnvelope>>(Wire.decode<CommandEnvelope>("""{"v":1,"command":{"kind":"dismiss_place","title":"Charminar"}}""")).value
        assertEquals(Command.DismissPlace("Charminar", null), old.command)
        // an older phone's card has no id
        val oldCard = assertIs<Overlay.Nudge>(Wire.json.decodeFromString<Overlay>("""{"kind":"nudge","text":"x: y","place":{"title":"x","line":"y","source":"s","nearby":false}}"""))
        assertEquals(null, oldCard.place!!.id)
        // an older phone ignores the new fields (ignoreUnknownKeys), and a card with no id does not write one
        assertFalse(Wire.encode<Overlay>(Overlay.Nudge("a: b", PlaceCardInfo("a", "b", "s"))).contains("\"id\""))
        assertEquals("Charminar", Wire.json.decodeFromString<LegacyDismiss>(Wire.encode<Command>(Command.DismissPlace("Charminar", "pc7"))).title)
        assertEquals(1, PROTOCOL_VERSION)
    }

    // v3.1: a delivered suggestion rides the SAME additive place-card fields (no new wire field; PROTOCOL_VERSION stays 1), both ways.
    @Test fun aSuggestionCardRoundTripsAndOldPeersInteroperate() {
        val o = Overlay.Nudge("Golconda: last visit 12 Nov.", PlaceCardInfo("Golconda", "last visit 12 Nov.", "Suggestion \u00b7 from 2 items \u00b7 12 Nov", nearby = false, id = "sg_0d9f"))
        assertEquals(o, assertIs<Decoded.Ok<StateFrame>>(Wire.decode<StateFrame>(Wire.encode(frame(o)))).value.overlay)
        // an older glasses build (before place cards) decodes only `text`: it shows the ordinary nudge text, never breaks
        assertEquals("Golconda: last visit 12 Nov.", Wire.json.decodeFromString<LegacyNudge>(Wire.encode<Overlay>(o)).text)
        // a current glasses build's dismissal of it names the card by its id; an older phone's decoder keeps working
        val e = CommandEnvelope(command = Command.DismissPlace("Golconda", "sg_0d9f"))
        assertEquals(e, assertIs<Decoded.Ok<CommandEnvelope>>(Wire.decode<CommandEnvelope>(Wire.encode(e))).value)
        assertEquals("Golconda", Wire.json.decodeFromString<LegacyDismiss>(Wire.encode<Command>(e.command)).title)
        // an older phone sends no suggestion at all: a plain nudge still decodes, with no place
        assertEquals(null, assertIs<Overlay.Nudge>(Wire.json.decodeFromString<Overlay>("""{"kind":"nudge","text":"Leave in 10 min"}""")).place)
        assertEquals(1, PROTOCOL_VERSION)
    }
}
