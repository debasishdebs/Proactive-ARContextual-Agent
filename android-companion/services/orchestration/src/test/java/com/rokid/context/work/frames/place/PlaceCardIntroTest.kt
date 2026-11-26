package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun pe(summary: String, source: String = "wikipedia:en", name: String = "Charminar", kind: String = "monument") =
    PlaceEntity("wd:Q1", null, name, kind, 1.0, 2.0, 40, summary, source, "p", 0, false, false, 0, 10)

/** V2a: an offline card shows the stored intro, inside the card's one 140-character line (the glasses limits are those of the same line). */
class PlaceCardIntroTest {
    private val three = "Charminar is a monument in Hyderabad. It was built in 1591. It has four minarets."

    @Test fun theIntroTakesWholeSentencesThatFit() {
        assertEquals(three, PlaceCards.intro(three, 140))
        assertEquals("Charminar is a monument in Hyderabad. It was built in 1591.", PlaceCards.intro(three, 70))
        assertEquals("Charminar is a monument in Hyderabad.", PlaceCards.intro(three, 40))
    }

    @Test fun aFirstSentenceThatIsTooLongIsCutAtAWordEndWithAnEllipsis() {
        val long = "The Charminar is a monument and mosque located in Hyderabad, Telangana, India, built in 1591 by Muhammad Quli Qutb Shah to mark the end of a deadly plague in the city of Hyderabad."
        val s = PlaceCards.intro(long, 140)
        assertTrue(s.length <= 140); assertTrue(s.endsWith("…")); assertFalse(s.dropLast(1).endsWith(" "))
    }

    @Test fun anEmptySummaryOrNoRoomGivesNothing() {
        assertEquals("", PlaceCards.intro("", 140)); assertEquals("", PlaceCards.intro("   ", 140)); assertEquals("", PlaceCards.intro(three, 5))
    }

    @Test fun theOfflineCardCarriesTheIntroThenTheDistanceAndNeverMoreThanOneLine() {
        val c = PlaceCards.nearby(pe(three), 80.0, "f")
        assertEquals("Charminar", c.title); assertEquals("$three · 80 m away", c.line); assertEquals(PlaceCards.NEARBY_LABEL, c.sourceLabel); assertEquals(PlaceConfidence.NEARBY, c.confidence)
        val long = PlaceCards.nearby(pe("A".repeat(60) + ". " + "B".repeat(60) + ". " + "C".repeat(60) + "."), 1234.0, "f")
        assertTrue(long.line, long.line.length <= PlaceCards.LINE_MAX); assertTrue(long.line.endsWith(" · 1230 m away"))
    }

    @Test fun noStoredIntroGivesTheDistanceAloneAsBefore() = assertEquals("80 m away", PlaceCards.nearby(pe(""), 80.0, "f").line)

    @Test fun aPinShowsItsOwnNote() {
        assertEquals("My note · here", PlaceCards.nearby(pe("My note", source = PlaceCards.USER_SOURCE), 0.0, "f").line)
    }
}
