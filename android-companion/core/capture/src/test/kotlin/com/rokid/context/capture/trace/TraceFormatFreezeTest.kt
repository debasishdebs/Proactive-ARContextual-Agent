package com.rokid.context.capture.trace

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.session.BudgetTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins schema 1: enum wire names, the tag and field lists, the sensor-time rule (C1) and the scheduler inputs (C2). A failure here means bump TRACE_SCHEMA. */
class TraceFormatFreezeTest {
    private val bump = "this changes trace schema 1: bump TRACE_SCHEMA (and regenerate the goldens deliberately)"

    @Test fun enumWireNamesFrozen() {
        assertEquals(listOf("UNKNOWN", "REST", "WALKING", "VEHICLE_PASSENGER", "VEHICLE_DRIVING", "FLIGHT"), ContextState.values().map { it.name }, bump)
        assertEquals(listOf("SETTINGS", "SHADOW", "TRIAL", "LIVE"), PlanMode.values().map { it.name }, bump)
        assertEquals(listOf("STILL", "HEAD_MOVING", "WALKING", "VEHICLE", "UNKNOWN"), MotionState.values().map { it.name }, bump)
        assertEquals(listOf("MOTION", "SETTLE"), CameraWake.values().map { it.name }, bump)
        assertEquals(listOf("T0", "T1", "T2", "T3"), BudgetTier.values().map { it.name }, bump)
        assertEquals(listOf("TIMER", "DWELL", "ONSET", "NOW", "RESV", "STREAM"), EpisodeCause.values().map { it.name }, bump)
        assertEquals(listOf("KEEP", "SKIP"), GateOutcome.values().map { it.name }, bump)
        assertEquals(listOf("OK", "OPEN_FAILED", "NO_FRAMES", "BUSY", "BIND", "COLD"), EpisodeOutcome.values().map { it.name }, bump)
        assertEquals(listOf("COMPLETE", "PARTIAL"), TraceStatus.values().map { it.name }, bump)
        assertEquals(setOf("BLUR", "DUPLICATE", "THROTTLED", "WINDOW", "CAPPED"), GATE_SKIP_REASONS, bump)
        assertEquals(1, TRACE_SCHEMA)
    }

    @Test fun tagAndFieldListsFrozen() {
        assertEquals(listOf("g", "a", "cx", "rs", "pf", "gp", "pl", "ep", "sc", "gt", "fl", "bo", "bl", "dk", "pr", "cl"), EventCodec.TAGS, bump)
        assertEquals(EventCodec.TAGS, EventCodec.FIELDS.keys.toList())
        assertEquals(listOf("sMs", "x", "y", "z"), EventCodec.FIELDS.getValue("g"))
        assertEquals(listOf("seq", "d", "p", "blur", "passedBlur", "motion", "dwellArmed", "force", "cap", "hard", "nowMs"), EventCodec.FIELDS.getValue("sc"))
        // every golden line has exactly tag + tMs + the declared fields, and every tag appears
        val lines = TraceFixtures.goldenPayload.lines().drop(1).filter { it.isNotEmpty() }
        assertEquals(EventCodec.TAGS.toSet(), lines.map { it.substring(2, it.indexOf('"', 2)) }.toSet())
        for (l in lines) {
            val tag = l.substring(2, l.indexOf('"', 2))
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(l) as kotlinx.serialization.json.JsonArray
            assertEquals(2 + EventCodec.FIELDS.getValue(tag).size, arr.size, l)
        }
        assertEquals(EventCodec.TAGS, TraceFixtures.events.map { EventCodec.tag(it) }.distinct().sortedBy { EventCodec.TAGS.indexOf(it) })
    }

    // ---- C1: sensor time ----
    @Test fun sensorTimeRoundTripsAndMayRegress() {
        // a STILL-rate batch: five samples delivered at 5000..5001, sensor times spread over the last 500 ms, gyro and accel interleaved out of order
        val batch = listOf(
            TraceEvent.Gyro(5000, 4500, 0.1f, 0f, 0f), TraceEvent.Gyro(5000, 4600, 0.2f, 0f, 0f), TraceEvent.Accel(5000, 4550, 0f, 0f, 9.8f),
            TraceEvent.Gyro(5001, 4700, 0.3f, 0f, 0f), TraceEvent.Accel(5001, 4520, 0f, 0f, 9.7f),   // sMs regresses against the previous accel
            TraceEvent.Gyro(5001, 4650, 0.4f, 0f, 0f),                                                   // and against the previous gyro
        )
        val enc = ChunkCodec.encode(TraceFixtures.ID, 0, batch)
        val back = ChunkCodec.decode(TraceFixtures.ID, 0, enc.sha256, enc.b64).getOrThrow().events
        assertEquals(batch, back)
        batch.forEach { assertEquals(it, EventCodec.decode(EventCodec.encode(it))) }
        // the line time is the delivery time (non-decreasing), the sensor time is its own field
        assertEquals(listOf(5000L, 5000, 5000, 5001, 5001, 5001), back.map { it.tMs })
        // sMs may be AFTER the delivery time too (clock skew is not the codec's business), but it is a plain non-negative time
        assertEquals(TraceEvent.Gyro(5, 9_000, 0f, 0f, 0f), EventCodec.decode("""["g",5,9000,0,0,0]"""))
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gyro(5, -1, 0f, 0f, 0f) }
    }

    @Test fun dtFromSensorTimeIsPreservedAcrossABatch() {
        // 25 ms apart in sensor time, all delivered at (nearly) the same append time
        val sensor = (0 until 20).map { 10_000L + it * 25 }
        val written = sensor.mapIndexed { i, s -> TraceEvent.Gyro(20_000L + i / 10, s, 0.5f, 0f, 0f) }
        val enc = ChunkCodec.encode(TraceFixtures.ID, 0, written)
        val back = ChunkCodec.decode(TraceFixtures.ID, 0, enc.sha256, enc.b64).getOrThrow().events.map { it as TraceEvent.Gyro }
        fun dts(xs: List<Long>) = xs.zipWithNext { a, b -> b - a }
        assertEquals(List(19) { 25L }, dts(back.map { it.sMs }))
        assertTrue(dts(back.map { it.tMs }).all { it in 0..1 }, "append-time dt is useless to the classifier (that is why sMs exists)")
        assertEquals(sensor, back.map { it.sMs })
    }

    // ---- M10: sampleEvery applies to g and a only ----
    @Test fun downsamplingTouchesOnlyGyroAndAccel() {
        val d = Downsampler(4)
        val input = (0 until 40).flatMap { i ->
            listOf(TraceEvent.Gyro(i * 10L, i * 10L, 0f, 0f, 0f), TraceEvent.Accel(i * 10L + 1, i * 10L, 0f, 0f, 0f), TraceEvent.Flush(i * 10L + 2, "window"), TraceEvent.Blocked(i * 10L + 3, null))
        }
        val kept = input.filter { d.keep(it) }
        assertEquals(10, kept.count { it is TraceEvent.Gyro }); assertEquals(10, kept.count { it is TraceEvent.Accel })
        assertEquals(40, kept.count { it is TraceEvent.Flush }); assertEquals(40, kept.count { it is TraceEvent.Blocked })
        assertEquals(0L, (kept.first { it is TraceEvent.Gyro } as TraceEvent.Gyro).sMs)   // the first sample of each is kept
        assertEquals(setOf(true), input.filter { it.isSensorSample }.map { it is TraceEvent.Gyro || it is TraceEvent.Accel }.toSet())
        val all = Downsampler(1); assertTrue(input.all { all.keep(it) })
        assertFailsWith<IllegalArgumentException> { Downsampler(0) }
    }

    // ---- C2: every scheduler input is in the trace ----
    @Test fun everySchedulerInputIsCarriedByTheTrace() {
        val snapshotKeys = Regex("\"([A-Za-z0-9]+)\":").findAll(TraceFixtures.goldenManifest.substringAfter("\"snapshot\":")).map { it.groupValues[1] }.toSet()
        // the inputs of CameraPolicy.decide, the SamplingSession tick/episode/frame loop and KeyframeGate.offerScored, as read in the glasses code
        val decide = listOf("state", "decelerating", "msSinceMotionEnd", "msSinceKeyframe", "quickLookMs", "lowPower", "heatSlow", "nowMs", "lastBriefCheckAtMs", "quiet", "profile", "stillStep")
        assertEquals(decide.map { "decide.$it" }, SchedulerInputs.ALL.keys.filter { it.startsWith("decide.") })
        for ((input, where) in SchedulerInputs.ALL) when {
            where.startsWith("derived:") -> Unit
            where.startsWith("snapshot.") -> assertTrue(where.removePrefix("snapshot.") in snapshotKeys, "$input -> $where is not a snapshot key")
            else -> {
                val (tag, field) = where.split('.').let { it[0] to it[1] }
                assertTrue(field in EventCodec.FIELDS.getValue(tag), "$input -> $where is not an event field")
            }
        }
        // the C2 list from the review, by name
        for (must in listOf("pf.quickLookMs", "dk.streak", "ep.outcome", "ep.openedAtMs", "sc.cap", "sc.hard", "snapshot.startups", "snapshot.lastBriefAt", "snapshot.stillStep",
            "snapshot.lastCheckHash", "snapshot.pose", "snapshot.lastPhotoAttemptAtMs", "snapshot.darkStreak", "snapshot.bindBackoffUntil", "snapshot.coldStartGrace")) {
            assertTrue(must in SchedulerInputs.ALL.values, must)
        }
    }

    @Test fun episodeAndScoreCarryTheirSchedulingFields() {
        val open = TraceEvent.Episode(10, true, EpisodeCause.TIMER)
        assertEquals("""["ep",10,true,"timer",null,null,null]""", EventCodec.encode(open))
        for (o in EpisodeOutcome.values()) {
            val close = TraceEvent.Episode(20, false, EpisodeCause.NOW, 15, 12, o)
            assertEquals(close, EventCodec.decode(EventCodec.encode(close)))
        }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Episode(10, true, EpisodeCause.TIMER, outcome = EpisodeOutcome.OK) }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Episode(10, false, EpisodeCause.TIMER) }
        // an episode that never opened the camera
        val failed = TraceEvent.Episode(30, false, EpisodeCause.TIMER, null, null, EpisodeOutcome.OPEN_FAILED)
        assertEquals(failed, EventCodec.decode(EventCodec.encode(failed)))
        val sc = TraceEvent.Score(1, 1, 1, 2, 3f, true, MotionState.WALKING, false, true, null, true, 9)
        assertEquals(sc, EventCodec.decode(EventCodec.encode(sc)))
        assertEquals(sc.copy(cap = 4), EventCodec.decode(EventCodec.encode(sc.copy(cap = 4))))
        assertEquals(TraceEvent.DarkStreak(5, 3), EventCodec.decode(EventCodec.encode(TraceEvent.DarkStreak(5, 3))))
    }

    @Test fun gateCrossFieldRule() {
        assertEquals("""["gt",1,2,"keep","",true,1]""", EventCodec.encode(TraceEvent.Gate(1, 2, GateOutcome.KEEP, "", keptAtMs = 1)))
        for (r in GATE_SKIP_REASONS) TraceEvent.Gate(1, 2, GateOutcome.SKIP, r)
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.KEEP, "BLUR", keptAtMs = 1) }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.SKIP, "") }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.SKIP, "OTHER") }
    }
}

class TraceFormatFreeze2Test {
    @Test fun anUnboundedDarkStreakIsClampedNotRefused() {
        var live = 0
        repeat(5_000) { live++ }                            // a device left face-down overnight
        assertEquals(MAX_DARK_STREAK, clampDarkStreak(live)); assertEquals(MAX_DARK_STREAK, clampDarkStreak(Int.MAX_VALUE)); assertEquals(0, clampDarkStreak(-3))
        val e = TraceEvent.DarkStreak(5, clampDarkStreak(live))
        assertEquals(e, EventCodec.decode(EventCodec.encode(e)))
        assertFailsWith<IllegalArgumentException> { TraceEvent.DarkStreak(5, live) }                       // unclamped is refused
        assertFailsWith<IllegalArgumentException> { TraceFixtures.snapshot.copy(darkStreak = live) }
        assertEquals(MAX_DARK_STREAK, TraceFixtures.snapshot.copy(darkStreak = clampDarkStreak(live)).darkStreak)
        assertTrue(clampDarkStreak(live) >= 3, "quiet (>= 3) is preserved by the clamp")
    }

    @Test fun contextCapAndHardArePartOfTheEffectiveProfile() {
        val pf = TraceFixtures.events.filterIsInstance<TraceEvent.Profile>().single()
        assertEquals(3, pf.ctxCap); assertTrue(pf.hard)
        val none = pf.copy(ctxCap = null, hard = false)
        assertEquals(none, EventCodec.decode(EventCodec.encode(none)))
        assertEquals(pf, EventCodec.decode(EventCodec.encode(pf)))
        assertFailsWith<IllegalArgumentException> { pf.copy(ctxCap = -1) }
    }

    @Test fun nowMsAndEncodeFailureAreRecorded() {
        val sc = TraceFixtures.events.filterIsInstance<TraceEvent.Score>().single()
        assertEquals(2490L, sc.nowMs)
        assertEquals(sc.copy(nowMs = 123), EventCodec.decode(EventCodec.encode(sc.copy(nowMs = 123))))
        val failed = TraceFixtures.events.filterIsInstance<TraceEvent.Gate>().single()
        assertEquals(false, failed.encoded)
        assertEquals(failed, EventCodec.decode(EventCodec.encode(failed)))
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 1, GateOutcome.SKIP, "BLUR", encoded = false) }
    }

    @Test fun replayEqualityRule() {
        // Task 23b ruling (deliberate change of the round-1 symmetric 250 ms): the live tick loop is only ever LATE against the replayed tick (wake-up latency,
        // measured +135..+259 ms on a real desk trace), so a recorded open may be up to LATE_SLACK_MS after the simulated one but only EARLY_SLACK_MS before it.
        assertEquals(250L, ReplayRule.EARLY_SLACK_MS)
        assertEquals(500L, ReplayRule.LATE_SLACK_MS)
        assertTrue(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 1750, EpisodeCause.TIMER))   // recorded 250 later than simulated
        assertTrue(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 1500, EpisodeCause.TIMER))   // recorded 500 later than simulated
        assertFalse(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 1499, EpisodeCause.TIMER))
        assertTrue(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 2250, EpisodeCause.TIMER))   // recorded 250 earlier than simulated
        assertFalse(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 2251, EpisodeCause.TIMER))
        assertFalse(ReplayRule.opensMatch(2000, EpisodeCause.TIMER, 2000, EpisodeCause.DWELL))
        // the real desk trace b653442d (M1): recorded 5313780 (+259 ms late) vs simulated 5313521 now matches; a recorded 5314100 (+579) does not
        assertTrue(ReplayRule.opensMatch(5313780, EpisodeCause.TIMER, 5313521, EpisodeCause.TIMER))
        assertFalse(ReplayRule.opensMatch(5314100, EpisodeCause.TIMER, 5313521, EpisodeCause.TIMER))
        val g = TraceEvent.Gate(1, 5, GateOutcome.KEEP, "", keptAtMs = 7)
        assertTrue(ReplayRule.gatesMatch(g, g.copy(tMs = 99)))        // append time is not part of the equality
        assertFalse(ReplayRule.gatesMatch(g, g.copy(encoded = false, keptAtMs = null)))
        assertFalse(ReplayRule.gatesMatch(g, g.copy(keptAtMs = 8)))
        assertFalse(ReplayRule.gatesMatch(g, TraceEvent.Gate(1, 5, GateOutcome.SKIP, "CAPPED")))
    }

    /** Every mapped field must really exist in the recorder model (a re-pointing to a renamed or missing field fails), and FIELDS must equal the model's fields in order. */
    @Test fun schedulerInputsPointAtFieldsTheRecorderModelWrites() {
        val classes = mapOf(
            "g" to TraceEvent.Gyro::class.java, "a" to TraceEvent.Accel::class.java, "cx" to TraceEvent.Ctx::class.java, "rs" to TraceEvent.Resting::class.java,
            "pf" to TraceEvent.Profile::class.java, "gp" to TraceEvent.GateParams::class.java, "pl" to TraceEvent.Plan::class.java, "ep" to TraceEvent.Episode::class.java,
            "sc" to TraceEvent.Score::class.java, "gt" to TraceEvent.Gate::class.java, "fl" to TraceEvent.Flush::class.java, "bo" to TraceEvent.Backoff::class.java,
            "bl" to TraceEvent.Blocked::class.java, "dk" to TraceEvent.DarkStreak::class.java, "pr" to TraceEvent.PreRollEnd::class.java, "cl" to TraceEvent.Close::class.java,
        )
        fun props(c: Class<*>) = c.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        assertEquals(EventCodec.TAGS.toSet(), classes.keys)
        for ((tag, cls) in classes) assertEquals(listOf("tMs") + EventCodec.FIELDS.getValue(tag), props(cls), tag)
        val snap = props(StartSnapshot::class.java).toSet()
        for ((input, where) in SchedulerInputs.ALL) {
            if (where.startsWith("derived:")) continue
            val (head, field) = where.split('.').let { it[0] to it[1] }
            if (head == "snapshot") assertTrue(field in snap, "$input -> $where is not a StartSnapshot field")
            else assertTrue(field in props(classes.getValue(head)), "$input -> $where is not written by the recorder model")
        }
        // and a snapshot input is really encoded in the manifest
        for ((input, where) in SchedulerInputs.ALL) if (where.startsWith("snapshot.")) assertTrue("\"${where.removePrefix("snapshot.")}\":" in TraceFixtures.goldenManifest, input)
    }
}

class TraceFormatFreeze3Test {
    @Test fun flushCarriesTheGateDecisionTime() {
        val f = TraceEvent.Flush(7000, "window", nowMs = 6999)
        assertEquals("""["fl",7000,"window",6999]""", EventCodec.encode(f))
        assertEquals(f, EventCodec.decode(EventCodec.encode(f)))
        assertFailsWith<TraceFormatException> { EventCodec.decode("""["fl",7000,"window"]""") }
    }

    @Test fun keepBookkeepingTimeIsSetExactlyForAnEncodedKeep() {
        val k = TraceEvent.Gate(10, 1, GateOutcome.KEEP, "", encoded = true, keptAtMs = 9)
        assertEquals("""["gt",10,1,"keep","",true,9]""", EventCodec.encode(k))
        assertEquals(k, EventCodec.decode(EventCodec.encode(k)))
        assertFailsWith<IllegalArgumentException> { k.copy(keptAtMs = null) }
        assertFailsWith<IllegalArgumentException> { k.copy(encoded = false) }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(10, 1, GateOutcome.SKIP, "BLUR", keptAtMs = 9) }
    }

    @Test fun snapshotCarriesBindFailuresAndThePreviousGyroTime() {
        val back = Manifest.decodeStrict(TraceFixtures.goldenManifest).getOrThrow().snapshot
        assertEquals(1, back.bindFailures); assertEquals(985L, back.prevGyroMs)
        assertFailsWith<IllegalArgumentException> { TraceFixtures.snapshot.copy(bindFailures = 101) }
        assertEquals(null, Manifest.decodeStrict(TraceFixtures.manifest(snap = TraceFixtures.snapshot.copy(prevGyroMs = null)).encode()).getOrThrow().snapshot.prevGyroMs)
    }

    @Test fun inexactWindowsAreMarkedAndCounted() {
        val ex = Exactness.classify(TraceFixtures.events.filter { it !is TraceEvent.Episode }.take(5))
        assertEquals(emptySet(), ex)
        val dwell = listOf(TraceEvent.Episode(1, true, EpisodeCause.DWELL))
        assertEquals(setOf(Inexact.ASYNC_CALLBACK), Exactness.classify(dwell))
        assertEquals(setOf(Inexact.CAPTURE_NOW), Exactness.classify(listOf(TraceEvent.Episode(1, true, EpisodeCause.NOW))))
        assertEquals(setOf(Inexact.CAPTURE_NOW), Exactness.classify(listOf(TraceEvent.Score(1, 1, 0, 0, 20f, true, MotionState.STILL, false, true, null, false, 1))))
        assertEquals(setOf(Inexact.BIND_FAILURE), Exactness.classify(listOf(TraceEvent.Episode(1, false, EpisodeCause.TIMER, null, null, EpisodeOutcome.BIND))))
        assertEquals(setOf(Inexact.BIND_FAILURE), Exactness.classify(emptyList(), TraceFixtures.snapshot))      // bindFailures = 1 and a back-off pending
        assertEquals(emptySet(), Exactness.classify(emptyList(), TraceFixtures.snapshot.copy(bindFailures = 0, bindBackoffUntil = null)))
        val t = ReplayTally()
        t.record(emptySet(), true); t.record(emptySet(), false); t.record(setOf(Inexact.CAPTURE_NOW), false); t.record(setOf(Inexact.BIND_FAILURE), true)
        assertEquals(1, t.passed); assertEquals(1, t.failed); assertEquals(2, t.inexact)
    }

    @Test fun phoneSampleAgeAndOffsetDriftAreAccountedFor() {
        val m = TraceFixtures.manifest()
        val ok = ClockMap.of(m)
        assertEquals(false, ok.phoneSampleStale); assertEquals(false, ok.phoneJump)
        assertEquals(m.startWallMs + 250, ok.phoneWall(1000, "42"))               // atMs + age - wall = 250
        assertEquals(m.endWallMs + 252, ok.phoneWall(12_000, "42"))
        assertEquals(40L + 10L + 2L + 20L, ok.uncertaintyMs)                      // sync + glasses drift + phone drift + max age
        // too old: the samples are not used, the intake offset is the fallback
        val stale = ClockMap.of(m.copy(endPhoneAgeMs = ClockMap.MAX_PHONE_AGE_MS + 1))
        assertTrue(stale.phoneSampleStale)
        assertEquals(m.startWallMs + 250, stale.phoneWall(1000, "42"))
        assertEquals(m.endWallMs + 250, stale.phoneWall(12_000, "42"))            // intake offset 250, not the samples
        assertEquals(null, ClockMap.of(m.copy(endPhoneAgeMs = ClockMap.MAX_PHONE_AGE_MS + 1, phoneOffsetMs = null, offsetUncertaintyMs = null)).phoneWall(1000, "42"))
        // the age bound itself is accepted
        assertEquals(false, ClockMap.of(m.copy(endPhoneAgeMs = ClockMap.MAX_PHONE_AGE_MS)).phoneSampleStale)
        // the phone clock was corrected between the samples
        val jumped = ClockMap.of(m.copy(endPhoneWallMs = m.endPhoneWallMs!! + 5_000))
        assertTrue(jumped.phoneJump); assertEquals(null, jumped.phoneWall(6_000, "42")); assertTrue(jumped.glassesWall(6_000, "42") != null)
    }

    @Test fun hostileTinyChunksAreRejectedEarlyAndKeepNothing() {
        val id = TraceFixtures.ID
        // 4,096 highly compressible chunks of repeated pr events against a manifest that says 20 events in total
        val manifest = TraceFixtures.manifest(chunkCount = 4096, eventCount = 4096, rawBytes = 5_000)
        val asm = TraceAssembler(manifest)
        val events = List(5_000) { TraceEvent.PreRollEnd(11_000) }
        val e0 = ChunkCodec.encode(id, 0, events)
        for (seq in 0 until 4096) {
            val e = ChunkCodec.encode(id, seq, events)
            asm.accept(ChunkCodec.decode(id, seq, e.sha256, e.b64).getOrThrow())
        }
        assertTrue(e0.b64.length < 1_000, "tiny on the wire")
        assertEquals(0L, asm.inspectedEvents, "an over-manifest chunk is never inspected")
        assertEquals("totals", asm.verify().reason)
        assertEquals(TraceStatus.PARTIAL, asm.verify().status)
        // within bounds, inspection is bounded by the manifest event count
        val ok = TraceAssembler(TraceFixtures.manifest(chunkCount = 1, eventCount = 17, rawBytes = TraceFixtures.GOLDEN_RAW_BYTES.toLong(), traceSha = TraceFixtures.GOLDEN_TRACE_SHA))
        ok.accept(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64).getOrThrow())
        assertEquals(17L, ok.inspectedEvents); assertEquals(TraceStatus.COMPLETE, ok.verify().status)
    }
}
