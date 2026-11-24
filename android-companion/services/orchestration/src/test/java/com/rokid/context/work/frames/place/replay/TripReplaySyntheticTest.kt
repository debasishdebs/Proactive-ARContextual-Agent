package com.rokid.context.work.frames.place.replay

import com.rokid.context.work.frames.place.PlaceMatchResult
import com.rokid.context.work.frames.place.PlaceOutcome
import com.rokid.context.ai.contract.PlaceMatchOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests of the harness on the synthetic bundle (always run, no environment needed). Times are offsets from T0 of the invented time base. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripReplaySyntheticTest {
    private val T0 = SyntheticBundle.T0
    private val H = 3_600_000L
    private val bundle = SyntheticBundle.load()
    private val X = T0 + 3 * H + 60_000L

    private fun replay(spec: RunSpec, b: ReplayBundle = bundle): ReplayResult {
        var out: ReplayResult? = null
        runTest { out = TripReplayRunner(b).run(this, spec) }
        return out!!
    }

    private fun rowsIn(r: ReplayResult, from: Long, to: Long, extra: String = "") = r.rows.filter { it.extra == extra && it.pokeMs in from..to }
    private val siteA = T0 - 60_000..T0 + 130_000
    private val rooms = T0 + H - 60_000..T0 + H + 120_000
    private val show = T0 + 2 * H..T0 + 2 * H + 2_400_000
    private val facade = X - 60_000..X + 30_000
    private val indoorE = T0 + 5 * H..T0 + 5 * H + 600_000

    /** The first model call is weak (NONE), every later one is the oracle. */
    private object FirstWeakOracle : MatchStage {
        override val label = "FirstWeakOracle"
        override fun create(bundle: ReplayBundle): ReplayMatcher {
            val oracle = Oracle.create(bundle); var calls = 0
            return object : BaseMatcher() { override suspend fun match(frameId: String, lines: List<String>): PlaceMatchResult =
                if (calls++ == 0) PlaceMatchResult(PlaceMatchOutput("NONE", 0.9f), true) else oracle.match(frameId, lines) }
        }
    }

    @Test fun siteExterior_weakFirstFrame_doesNotBlockTheGoodOne() {
        val r = replay(RunSpec(FirstWeakOracle, FixScenario.Outdoor10))
        val rows = rowsIn(r, siteA.first, siteA.last)
        assertEquals(listOf(PlaceOutcome.NONE, PlaceOutcome.MATCHED, PlaceOutcome.DUPLICATE), rows.map { it.outcome })
        assertEquals("Site A", rows[1].chosenName); assertTrue(rows[1].isCard)
        assertEquals("same_place_recently", rows[2].reason)
    }

    @Test fun nestedRoomPins_indoorFix_neverShowsAWrongRoom() {
        val scenarios = listOf(FixScenario.AsRecorded, FixScenario.Outdoor10, FixScenario.Indoor60, FixScenario.Stale, FixScenario.AssumedPin(60f))
        val overlays = listOf(Overlays.HEAD, Overlays.F1, Overlays.f2(10), Overlays.ALL)
        for (sc in scenarios) for (ov in overlays) {
            val r = replay(RunSpec(Oracle, sc, overlays = ov))
            for (row in rowsIn(r, rooms.first, rooms.last)) {
                if (row.isCard) assertEquals("${r.spec.label}", "Room A2", row.chosenName)
                if (row.f1.startsWith("card")) assertEquals("${r.spec.label}", "card Room A2", row.f1)
            }
        }
        val indoor = rowsIn(replay(RunSpec(Oracle, FixScenario.Indoor60)), rooms.first, rooms.last)
        assertTrue(indoor.all { it.outcome == PlaceOutcome.STALE_FIX && it.reason == "fix_inaccurate" })
        val outdoor = rowsIn(replay(RunSpec(Oracle, FixScenario.Outdoor10)), rooms.first, rooms.last)
        assertTrue("an outdoor fix in the room gives a card", outdoor.any { it.isCard })
    }

    @Test fun nestedRoomPins_f1_ambiguousInsteadOfGuess() {
        val a1 = bundle.pins.first { it.name == "Room A1" }; val a2 = bundle.pins.first { it.name == "Room A2" }
        val mid = (a1.lat + a2.lat) / 2
        val pins = bundle.pins.filter { it.name.startsWith("Room") }
        val rule = PinFirstRule()
        val d = rule.decide(T0, com.rokid.context.work.frames.place.PlaceFix(mid, a1.lon, 3_000, 10f), pins)
        assertEquals("ambiguous(2)", d.text)
        val c = PinFirstRule().decide(T0, com.rokid.context.work.frames.place.PlaceFix(a2.lat, a2.lon, 3_000, 10f), pins)
        assertEquals("card Room A2", c.text)
        assertEquals("-", PinFirstRule().decide(T0, com.rokid.context.work.frames.place.PlaceFix(a2.lat, a2.lon, 3_000, 40f), pins).text) // acc > 25
    }

    @Test fun seatedShow_dedupeV2_oneCardPerPlaceWhileStaying() {
        val head = rowsIn(replay(RunSpec(Oracle, FixScenario.Outdoor10)), show.first, show.last)
        assertTrue("no storm: at most one card per 30 min", head.count { it.isCard } in 1..2)
        assertTrue("the rest are suppressed or duplicates", head.count { it.isSuppressed } + head.count { it.outcome == PlaceOutcome.DUPLICATE } >= 20)
        assertTrue(head.filter { it.isSuppressed }.all { it.cardWhy == "already_shown_here" && it.outcome == PlaceOutcome.MATCHED })
    }

    @Test fun pinCreatedAfterFacade_retryLooksAgainAtTheFrameInView() {
        val r = replay(RunSpec(Oracle, FixScenario.Outdoor10))
        val rows = rowsIn(r, facade.first, facade.last)
        assertEquals(listOf(PlaceOutcome.NO_CANDIDATES, PlaceOutcome.NO_CANDIDATES, PlaceOutcome.SEEN), rows.map { it.outcome })
        val extra = r.rows.filter { it.extra == "+F4" }
        assertEquals(1, extra.size)
        assertEquals(PlaceOutcome.MATCHED, extra[0].outcome); assertEquals("Site D", extra[0].chosenName); assertEquals(X + 1_000, extra[0].pokeMs)
        assertTrue(extra[0].attemptLine.contains("retry=pin_created"))
    }

    @Test fun indoorAfterOneGoodFix_f2_reusesWithinNMinutesOnly() {
        val none = rowsIn(replay(RunSpec(Oracle, FixScenario.Indoor60)), indoorE.first, indoorE.last)
        assertTrue(none.none { it.isCard })
        val f5 = rowsIn(replay(RunSpec(Oracle, FixScenario.Indoor60, overlays = Overlays.f2(5))), indoorE.first, indoorE.last)
        assertTrue(f5[0].isCard && f5[0].f2 && f5[0].chosenName == "Site E")
        assertFalse("the second frame has no fix inside N", f5[1].isCard)
        val f1 = rowsIn(replay(RunSpec(Oracle, FixScenario.Indoor60, overlays = Overlays.f2(1))), indoorE.first, indoorE.last)
        assertTrue("a 2 min old fix is beyond a 1 min window", f1.none { it.isCard })
    }

    @Test fun allCombiner_fallsBackToOracleOnCaptionMiss() {
        // every synthetic caption is "plain text": it names no place, so the combined stage must behave as the oracle (H1)
        val r = replay(RunSpec(ReplayReport.CaptionTextOrOracle, FixScenario.Outdoor10))
        val rows = rowsIn(r, siteA.first, siteA.last)
        assertTrue(rows[0].isCard); assertEquals("Site A", rows[0].chosenName)
        assertEquals("caption-only is the old behaviour: no card", PlaceOutcome.NONE, replay(RunSpec(CaptionText, FixScenario.Outdoor10)).rows.first { it.pokeMs in siteA }.outcome)
    }

    @Test fun indoorMediaGood_givesGoodFixOnlyNearMediaFix() {
        val f = ReplayFrame("s9001", T0 + 5 * H + 30_000, T0 + 5 * H + 29_000, true, "STILL", "ss1", -1, true, false, "")
        val b = bundle.copy(frames = bundle.frames + f)
        val rows = rowsIn(replay(RunSpec(Oracle, FixScenario.IndoorMediaGood), b), indoorE.first, indoorE.last)
        val near = rows.first { it.frameId == "s9001" }
        assertTrue("a media fix 30 s before gives a good fix and a card", near.isCard); assertEquals("Site E", near.chosenName)
        val far = rows.first { it.frameId == "s0050" }
        assertEquals("a fix 120 s old is only the inaccurate one", "fix_inaccurate", far.reason)
        assertEquals(PlaceOutcome.STALE_FIX, far.outcome)
    }

    @Test fun noPinNoCandidates_reportedNotTestable() {
        val span = bundle.truth.first { it.label == "NOPIN" }
        val rows = rowsIn(replay(RunSpec(Oracle, FixScenario.Outdoor10)), span.startMs, span.endMs)
        assertEquals(listOf(PlaceOutcome.NO_CANDIDATES), rows.map { it.outcome })
        assertTrue(ReplayReport.spanVerdict(span, rows).startsWith("not testable"))
    }

    @Test fun gpsGapBeyondN_isUnknownNotInvented() {
        val rows = rowsIn(replay(RunSpec(Oracle, FixScenario.Outdoor10)), indoorE.first, indoorE.last)
        assertEquals("UNKNOWN", rows[1].pos); assertEquals(PlaceOutcome.NO_FIX, rows[1].outcome); assertEquals("fix_unavailable", rows[1].reason)
    }

    @Test fun interpolation_onlyWithin5MinEachSideAnd200m() {
        fun fx(sec: Long, m: Double) = ReplayFix(T0 + sec * 1000, 10.0 + m / 111_320.0, 20.0, "CAMERA", "x")
        val inside = ReplayTrack(listOf(fx(0, 0.0), fx(480, 100.0)))
        assertEquals(PosSrc.INTERP, inside.near(T0 + 240_000)!!.src)               // 4 min each side, 0.2 m/s
        assertEquals(PosSrc.NEAREST, inside.near(T0 + 100_000)!!.src)              // only one side within 5 min
        assertNull(ReplayTrack(listOf(fx(0, 0.0), fx(480, 300.0))).near(T0 + 240_000)) // 300 m apart
        assertNull(inside.near(T0 + 900_000))                                       // beyond N both sides
    }

    @Test fun assumedPositionRows_areLabelledAndExcludedFromBackfill() {
        val spanE = bundle.truth.first { it.label == "INDOOR_E" }
        val b = bundle.copy(fixes = bundle.fixes.filter { it.tMs < spanE.startMs - 60_000 || it.tMs > spanE.endMs + 60_000 }, truth = bundle.truth.map { if (it.label == "INDOOR_E") it.copy(testable = "assumed-fix-only") else it })
        val r = replay(RunSpec(Oracle, FixScenario.AssumedPin(10f)), b)
        val rows = rowsIn(r, indoorE.first, indoorE.last)
        assertTrue(rows.isNotEmpty() && rows.all { it.assumed && it.pos.startsWith("ASSUMED") })
        assertTrue("an assumed fix still gives the card, labelled", rows.any { it.isCard })
        assertNull("backfill never uses it", ReplayTrack(b.fixes).backfill(spanE.startMs + 120_000))
        assertTrue(ReplayReport.spanVerdict(spanE.copy(testable = "assumed-fix-only"), rows).contains("ASSUMED"))
    }

    @Test fun historicalStage_reproducesRecordedMatches() {
        val h = bundle.historical.single()
        val r = replay(RunSpec(Historical, FixScenario.Outdoor10))
        val row = r.rows.first { it.frameId == h.frameId }
        assertEquals(PlaceOutcome.MATCHED, row.outcome); assertEquals(h.name, row.chosenName)
        val cal = ReplayReport.calibrate(bundle, r)
        assertEquals(1, cal.reproduced); assertTrue(cal.trusted)
    }

    @Test fun oracle_reportsTruthNotInCandidates() {
        val b = bundle.copy(truth = bundle.truth.map { if (it.label == "SITE_EXT") it.copy(target = "Nowhere") else it })
        val rows = rowsIn(replay(RunSpec(Oracle, FixScenario.Outdoor10), b), siteA.first, siteA.last)
        assertEquals("truth_not_in_candidates", rows[0].note)   // the model was asked once; the later frames are DUPLICATE and never reach it
        assertTrue(rows.none { it.isCard })
    }

    @Test fun realModelStage_isOffByDefault_andNeverReadsPhotos() {
        assertFalse(RealModelStage.enabled)
        var threw = false
        try { RealModelStage.create() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
        assertTrue(ReplayBundle.FILES.all { it.endsWith(".tsv") })
        assertNotNull(SyntheticBundle.dir())
    }
}
