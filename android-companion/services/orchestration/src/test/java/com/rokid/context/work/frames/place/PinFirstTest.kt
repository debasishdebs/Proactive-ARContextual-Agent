package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of F1 (pin-first and clusters). Synthetic names only. */
class PinFirstTest {
    private fun pin(id: Int, name: String, r: Int, parent: String? = null) =
        PlaceEntity("user:00000000-0000-0000-0000-%012d".format(id), parent, name, "monument", 10.0, 20.0, r, "", "user", "user", 0, false, true, 0, 0)
    private fun fix(acc: Float = 10f, age: Long = 3_000) = PlaceFix(10.0, 20.0, age, acc)
    private val v = PinVisits()

    @Test fun single_inside_fresh_card() {
        val a = pin(1, "Site A", 30)
        val d = PinFirst.decide(fix(), "fresh", listOf(a to 5.0), v, 0)
        assertTrue(d is PinFirst.Decision.Single && d.pin.id == a.id)
    }

    @Test fun smallRadius_neverCards() {
        assertEquals(PinFirst.Decision.SmallRadius, PinFirst.decide(fix(), "fresh", listOf(pin(1, "Object A", 10) to 2.0), v, 0))
    }

    @Test fun reusedFix_neverCards() {
        assertEquals(PinFirst.Decision.NotFresh, PinFirst.decide(fix(), "reused", listOf(pin(1, "Site A", 30) to 5.0), v, 0))
    }

    @Test fun acc30_notFresh() {
        assertEquals(PinFirst.Decision.NotFresh, PinFirst.decide(fix(acc = 30f), "fresh", listOf(pin(1, "Site A", 30) to 5.0), v, 0))
        assertEquals(PinFirst.Decision.NotFresh, PinFirst.decide(fix(age = 61_000), "fresh", listOf(pin(1, "Site A", 30) to 5.0), v, 0))
    }

    @Test fun outsideEveryPin_none() {
        assertEquals(PinFirst.Decision.None, PinFirst.decide(fix(), "fresh", listOf(pin(1, "Site A", 30) to 200.0), v, 0))
        assertEquals(PinFirst.Decision.None, PinFirst.decide(fix(), "fresh", emptyList(), v, 0))
    }

    @Test fun twoRoomsClose_clusterNotRoom() {
        val a = pin(1, "Room A1", 15); val b = pin(2, "Room A2", 20)
        val d = PinFirst.decide(fix(), "fresh", listOf(a to 3.0, b to 30.0), v, 0)
        assertTrue(d is PinFirst.Decision.Cluster && d.members.size == 2 && d.inside.id == a.id)
        assertEquals("cluster(2)", d.token)
        // a second pin far away is no neighbour
        assertTrue(PinFirst.decide(fix(), "fresh", listOf(a to 3.0, b to 120.0), v, 0) is PinFirst.Decision.Single)
    }

    @Test fun cluster_label_sharedToken() {
        assertEquals("Kestrel", ClusterLabel.of(listOf("Kestrel House", "Kestrel Founders gallery", "Bronze Gallery KestrelHouse", "Ivory Room KH")))
        assertEquals("Kestrel House", ClusterLabel.of(listOf("Kestrel House", "House of Kestrel")))
    }

    @Test fun cluster_label_parent() {
        assertEquals("Site A", ClusterLabel.of(listOf("Bronze Gallery", "Ivory Room"), listOf("Site A", "Site A")))
    }

    @Test fun cluster_label_fallback() {
        assertEquals("Your pins", ClusterLabel.of(listOf("Bronze Gallery", "Ivory Room"), listOf("Site A", "Site B")))
        assertEquals("Your pins", ClusterLabel.of(listOf("Bronze Gallery", "Ivory Room"), listOf(null, null)))
        val card = PlaceCards.cluster(listOf(pin(1, "Bronze Gallery", 15), pin(2, "Ivory Room", 15)), listOf(null, null), "f")
        assertEquals("Several pins", card.title); assertEquals(PlaceConfidence.PIN, card.confidence)
    }

    @Test fun visit_once_then_after10minOutside_again() {
        val a = pin(1, "Site A", 30)
        val spot = PinVisits.Spot(a.id, 10.0, 20.0, 30)
        v.begin(listOf(spot), 0)
        assertEquals(PinFirst.Decision.Visit, PinFirst.decide(fix(), "fresh", listOf(a to 5.0), v, 1_000))
        v.update(10.0 + 200 / 111_320.0, 20.0, 60_000)                       // 200 m away: outside r + 50 m
        assertTrue(v.has(listOf(a.id), 60_000 + 9 * 60_000))
        assertFalse(v.has(listOf(a.id), 60_000 + 10 * 60_000))
        assertTrue(PinFirst.decide(fix(), "fresh", listOf(a to 5.0), v, 60_000 + 10 * 60_000) is PinFirst.Decision.Single)
    }

    @Test fun visit_returnInsideResetsTheTimer() {
        val spot = PinVisits.Spot("p", 10.0, 20.0, 30)
        v.begin(listOf(spot), 0)
        v.update(10.0 + 200 / 111_320.0, 20.0, 60_000); v.update(10.0, 20.0, 300_000)  // back inside
        assertTrue(v.has(listOf("p"), 60_000 + 15 * 60_000))
    }

    @Test fun visit_cap3h() {
        v.begin(listOf(PinVisits.Spot("p", Double.NaN, Double.NaN, 0)), 0)
        assertTrue(v.has(listOf("p"), PinFirst.VISIT_MAX_MS - 1)); assertFalse(v.has(listOf("p"), PinFirst.VISIT_MAX_MS))
    }

    @Test fun visitHoldsForClustersContainingThePin() {
        val a = pin(1, "Room A1", 15); val b = pin(2, "Room A2", 20)
        v.begin(listOf(PinVisits.Spot(a.id, 10.0, 20.0, 15)), 0)
        assertEquals(PinFirst.Decision.Visit, PinFirst.decide(fix(), "fresh", listOf(a to 3.0, b to 30.0), v, 1_000))
    }
}
