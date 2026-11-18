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
}
