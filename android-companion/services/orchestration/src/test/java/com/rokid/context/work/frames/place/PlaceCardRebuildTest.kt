package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.PlaceKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card of a history row is rebuilt from the pack by place id (B2). Synthetic names only. */
class PlaceCardRebuildTest {
    private val site = PlaceEntity("wd:Q1", null, "Site A", PlaceKinds.ARCHAEOLOGICAL_SITE, 1.0, 2.0, 100, "A big old place. It has more text.", "wikipedia:en", "r", 0, false, false, 0, 10)
    private val pin1 = PlaceEntity("user:00000000-0000-0000-0000-000000000001", "wd:Q1", "Room A1", PlaceKinds.ARCHAEOLOGICAL_SITE, 1.0, 2.0, 20, "My own note.", "user", "", 0, false, true, 0, 10)
    private val pin2 = PlaceEntity("user:00000000-0000-0000-0000-000000000002", "wd:Q1", "Room A2", PlaceKinds.ARCHAEOLOGICAL_SITE, 1.0, 2.0, 20, "", "user", "", 0, false, true, 0, 10)
    private val places = listOf(site, pin1, pin2)
    private fun row(kind: String, place: String, frame: String? = "f1", members: String? = null) =
        PlaceCardLogEntity("h", 1, place, "Title", kind, members, frame, null, "c", CardDelivery.SENT)

    @Test fun photoRowGivesThePackLine() {
        val c = PlaceCardRebuild.card(row("PHOTO", "wd:Q1"), places)!!
        assertEquals("Site A", c.title); assertEquals("A big old place.", c.line); assertEquals(PlaceConfidence.PHOTO, c.confidence); assertEquals("f1", c.frameId)
    }

    @Test fun nearbyRowIsMarkedNearbyAndShowsTheStoredIntro() {
        val c = PlaceCardRebuild.card(row("NEARBY", "wd:Q1"), places)!!
        assertEquals(PlaceConfidence.NEARBY, c.confidence); assertEquals(PlaceCards.NEARBY_LABEL, c.sourceLabel); assertEquals("A big old place. It has more text.", c.line) // V2a
    }

    @Test fun pinRowUsesTheUsersNoteOrAFixedFallback() {
        assertEquals("My own note.", PlaceCardRebuild.card(row("PIN", pin1.id, null), places)!!.line)
        assertEquals("Your pin", PlaceCardRebuild.card(row("PIN", pin2.id, null), places)!!.line)
        assertEquals("Your pin", PlaceCardRebuild.card(row("PIN", pin1.id, null), places)!!.sourceLabel)
        assertEquals("", PlaceCardRebuild.card(row("PIN", pin1.id, null), places)!!.frameId)
    }

    @Test fun textRowIsLabelledAsASignRead() {
        assertEquals("Sign read · your pin", PlaceCardRebuild.card(row("TEXT", pin1.id, null), places)!!.sourceLabel)
    }

    @Test fun clusterRowListsUpToThreeMemberNamesUnderTheStoredTitle() {
        val c = PlaceCardRebuild.card(row("PIN_CLUSTER", pin1.id, null, "${pin1.id},${pin2.id}"), places)!!
        assertEquals("Title", c.title); assertEquals("Room A1 · Room A2", c.line); assertEquals("Your pins", c.sourceLabel)
        assertEquals(listOf(pin1.id, pin2.id), PlaceCardRebuild.idsOf(row("PIN_CLUSTER", pin1.id, null, "${pin1.id},${pin2.id}")))
        assertEquals("a cluster with one member left still shows it", "Room A2", PlaceCardRebuild.card(row("PIN_CLUSTER", pin1.id, null, "${pin1.id},${pin2.id}"), listOf(pin2))!!.line)
    }

    @Test fun aGonePlaceGivesNoCard() {
        assertNull(PlaceCardRebuild.card(row("PHOTO", "wd:Q404"), places))
        assertNull(PlaceCardRebuild.card(row("PIN_CLUSTER", pin1.id, null, "${pin1.id},${pin2.id}"), emptyList()))
        assertNotNull(PlaceCardRebuild.card(row("PHOTO", "wd:Q1"), places))
    }

    @Test fun publishIfIdNamesTheNewCardAndRespectsTheEpoch() {
        val h = PlaceCardHolder(); val card = PlaceCardRebuild.card(row("PHOTO", "wd:Q1"), places)!!
        val e = h.epoch(); val id = h.publishIfId(card, e)
        assertEquals(id, h.card.value!!.cardId)
        h.clear()
        assertNull("a withdrawal since the epoch fences it", h.publishIfId(card, e))
    }
}
