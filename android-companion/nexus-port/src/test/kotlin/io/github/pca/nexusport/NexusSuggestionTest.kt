package io.github.pca.nexusport

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** v3.1: a delivered suggestion is an ordinary Nudge notice through the sink; its gates apply as they are, a dismissal is reported by ref, and a withdrawal by ref hides only that notice. */
class NexusSuggestionTest {
    private val t = FakeNexusTransport()
    private val g = FakeGates()
    private val dismissed = mutableListOf<String>()
    private val handler = object : NexusActionHandler {
        override fun mark(ref: String) {}
        override fun more(ref: String) {}
        override fun dismiss(ref: String) { dismissed += ref }
    }
    private var now = 1_000L
    private fun sink() = NexusHudSink(t, g, { ON }, handler, { now })
    private fun sg(ref: String = "sg_1", src: Set<String> = setOf("m1")) = HudEvent.Nudge(ref, "Suggestion", "Golconda: last visit 12 Nov.\nfrom 2 items", src, 8_000)

    @Test fun theSinksOwnGatesStillApplyToASuggestion() = runBlocking<Unit> {
        g.worn = false; assertEquals(DropReason.NOT_WORN, assertIs<Outcome.Dropped>(sink().show(sg())).reason); g.worn = true
        g.liveIds = setOf("other"); assertEquals(DropReason.PROVENANCE, assertIs<Outcome.Dropped>(sink().show(sg())).reason); g.liveIds = null
        g.zone = true; assertEquals(DropReason.ZONE_HELD, assertIs<Outcome.Dropped>(sink().show(sg())).reason)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aDismissOnNexusIsReportedByRefOnce() = runBlocking<Unit> {
        val s = sink(); s.show(sg())
        s.events.onAction(Slot.NOTICE.code, "dismiss"); s.events.onAction(Slot.NOTICE.code, "dismiss")
        assertEquals(listOf("sg_1"), dismissed); assertEquals(0, s.liveHandles())
    }

    @Test fun withdrawByRefHidesOnlyThatNotice() = runBlocking<Unit> {
        val s = sink(); s.show(sg("sg_9"))
        s.withdraw("someone_else"); assertEquals(1, s.liveHandles())
        s.withdraw("sg_9"); assertEquals(0, s.liveHandles()); assertTrue("hide:NOTICE" in t.calls)
        s.withdraw("sg_9") // nothing left: no error
    }

    // ---- Codex v3.1 r2 P1-1: the suggestion's own gate runs inside the sink's FINAL admission ---------------------------------------------------------------------

    private fun gated(ref: String = "sg_1", gate: SuggestionGate) = sg(ref).copy(gate = gate)

    @Test fun anOpenGateShowsAndAClosedOrThrowingGateDropsBeforeAnythingIsQueued() = runBlocking<Unit> {
        assertIs<Outcome.Shown>(sink().show(gated { true })); t.calls.clear()
        assertEquals(DropReason.SUGGESTION_GATE, assertIs<Outcome.Dropped>(sink().show(gated("sg_2") { false })).reason)
        assertEquals(DropReason.SUGGESTION_GATE, assertIs<Outcome.Dropped>(sink().show(gated("sg_3") { throw IllegalStateException("boom") })).reason)
        assertTrue(t.calls.isEmpty(), "nothing was sent")
    }

    @Test fun drivingBecomingActiveWhileTheSinkSuspendsForProvenanceDropsTheShow() = runBlocking<Unit> {
        var driving = false
        g.sourcesGate = { driving = true } // publishable() was true; driving starts during the provenance read
        assertEquals(DropReason.SUGGESTION_GATE, assertIs<Outcome.Dropped>(sink().show(gated { !driving })).reason)
        assertTrue(t.calls.isEmpty()); assertEquals(0, sink().liveHandles())
    }

    @Test fun theGateIsAskedAgainRightBeforeTheTransportCall() = runBlocking<Unit> {
        var asked = 0
        val o = sink().show(gated { asked++; asked == 1 }) // open at the admission, closed by the time the dispatcher is about to call
        assertEquals(DropReason.TRIPPED, assertIs<Outcome.Dropped>(o).reason)
        assertTrue(asked >= 2, "asked at the admission and again before the call"); assertTrue(t.calls.none { it == "show:NOTICE" }, "nothing was sent")
    }

    @Test fun anEventWithoutAGateIsUntouched() = runBlocking<Unit> { assertIs<Outcome.Shown>(sink().show(sg())) }

    // ---- Codex v3.1 r3 P1-2: an already-published suggestion handle carries its gate; tick() hides it when the gate closes ---------------------------------------------------

    @Test fun aPublishedSuggestionHidesAtTheNextTickWhenItsGateCloses() = runBlocking<Unit> {
        var open = true
        val s = sink(); assertIs<Outcome.Shown>(s.show(gated { open })); assertEquals(1, s.liveHandles())
        s.tick(); assertEquals(1, s.liveHandles(), "the gate is open: the card stays"); assertTrue("hide:NOTICE" !in t.calls)
        open = false // driving started, quiet hours began, a switch went off or the record was withdrawn
        s.tick(); assertTrue("hide:NOTICE" in t.calls, "the closed gate hid the card"); assertEquals(0, s.liveHandles())
    }

    @Test fun aThrowingGateHidesAPublishedSuggestionAtTheNextTick() = runBlocking<Unit> {
        var boom = false
        val s = sink(); assertIs<Outcome.Shown>(s.show(gated { if (boom) throw IllegalStateException("x"); true }))
        boom = true; s.tick(); assertTrue("hide:NOTICE" in t.calls); assertEquals(0, s.liveHandles())
    }

    @Test fun anUngatedNoticeIsNotAffectedByTheGateRecheck() = runBlocking<Unit> {
        val s = sink(); assertIs<Outcome.Shown>(s.show(sg())); s.tick(); assertEquals(1, s.liveHandles()); assertTrue("hide:NOTICE" !in t.calls)
    }
}
