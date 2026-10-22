package com.rokid.context.capture.trace

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.session.BudgetTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        assertEquals(listOf("seq", "d", "p", "blur", "passedBlur", "motion", "dwellArmed", "force", "cap", "hard"), EventCodec.FIELDS.getValue("sc"))
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
        val sc = TraceEvent.Score(1, 1, 1, 2, 3f, true, MotionState.WALKING, false, true, null, true)
        assertEquals(sc, EventCodec.decode(EventCodec.encode(sc)))
        assertEquals(sc.copy(cap = 4), EventCodec.decode(EventCodec.encode(sc.copy(cap = 4))))
        assertEquals(TraceEvent.DarkStreak(5, 3), EventCodec.decode(EventCodec.encode(TraceEvent.DarkStreak(5, 3))))
    }

    @Test fun gateCrossFieldRule() {
        assertEquals("""["gt",1,2,"keep",""]""", EventCodec.encode(TraceEvent.Gate(1, 2, GateOutcome.KEEP, "")))
        for (r in GATE_SKIP_REASONS) TraceEvent.Gate(1, 2, GateOutcome.SKIP, r)
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.KEEP, "BLUR") }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.SKIP, "") }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gate(1, 2, GateOutcome.SKIP, "OTHER") }
    }
}
