package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun place(id: String, name: String, desc: String) = PlaceEntity(id, null, name, "monument", 1.0, 2.0, 40, desc, "wikipedia:en", "p", 0, false, false, 0, 10)
private fun img(tag: String) = L2Image("b64-$tag", "ref-$tag")

/** Layer 2 request (owner spec 28 Nov): the camera photo, the reference photos and the text of every candidate, in one call. */
class PlaceL2RequestTest {
    @Test fun carriesTheCameraPhotoPlusEveryReferencePhotoAndTheTextOfEachCandidate() {
        val c = listOf(
            L2Candidate(place("a", "Alpha Fort", "A hill fort."), listOf(img("a1"), img("a2"))),
            L2Candidate(place("b", "Beta Mosque", "A white mosque."), listOf(img("b1"))),
        )
        val r = PlaceL2Request.build(img("cam"), c)
        assertEquals(listOf("ref-cam", "ref-a1", "ref-a2", "ref-b1"), r.images.map { it.ref }) // camera FIRST, then the references in the order the text names them
        assertEquals(3, r.refCount); assertEquals(2, r.withRefs)
        val text = r.lines.joinToString("\n")
        assertTrue(text, "Image 1 = the camera photo" in text)
        assertTrue(text, "1. Alpha Fort | monument | A hill fort. | reference photos Image 2, Image 3" in text)
        assertTrue(text, "2. Beta Mosque | monument | A white mosque. | reference photo Image 4" in text)
        assertTrue("asks for none when unsure", "none" in r.lines.first())
    }

    @Test fun aCandidateWithoutAReferencePhotoIsStillSentWithItsText() {
        val r = PlaceL2Request.build(img("cam"), listOf(L2Candidate(place("a", "Alpha", "Old well."), emptyList()), L2Candidate(place("b", "Beta", "Tower."), listOf(img("b1")))))
        assertEquals(2, r.images.size); assertEquals(1, r.withRefs)
        assertTrue(r.lines.any { it == "1. Alpha | monument | Old well. | no reference photo" })
        assertTrue(r.lines.any { it.startsWith("2. Beta | monument | Tower. | reference photo Image 2") })
    }

    @Test fun capsTwoPerCandidateAndEightInTotalAndStillListsEveryCandidate() {
        val c = (1..6).map { i -> L2Candidate(place("p$i", "P$i", "d$i"), (1..3).map { img("p$i-$it") }) }
        val r = PlaceL2Request.build(img("cam"), c)
        assertEquals(1 + PlaceL2Request.MAX_REFS, r.images.size)
        assertEquals(6, r.lines.count { it[0].isDigit() }) // all six candidates are in the text
        assertTrue(r.lines.last().endsWith("no reference photo")) // the cap ran out: text only
        assertTrue(r.lines.first { it.startsWith("1.") }.contains("Image 2, Image 3")) // 2 per candidate, not 3
    }
}
