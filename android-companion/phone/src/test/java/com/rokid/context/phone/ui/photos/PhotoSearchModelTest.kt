package com.rokid.context.phone.ui.photos

import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import com.rokid.context.phone.ui.Fixtures.at
import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.ImageSearchState
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import com.rokid.context.work.frames.recall.RecallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSearchModelTest {
    private fun hit(
        id: String, source: PhotoSource = PhotoSource.IMAGE, score: Double = 0.5, ts: Long? = at(TODAY, 9, 41), receivedAt: Long? = at(TODAY, 14, 2),
        stored: Boolean = true, described: Boolean = false, captionId: String? = null, caption: String? = null, cluster: String? = null,
    ) = PhotoHit(id, source, score, ts, receivedAt, stored, described, captionId, caption, cluster)

    private fun result(
        image: List<PhotoHit> = emptyList(), captions: List<PhotoHit> = emptyList(), state: ImageSearchState = ImageSearchState.RAN,
        scanned: Int = 10, truncated: Boolean = false,
    ) = RecallResult(image, captions, state, scanned, truncated)

    private fun tiles(r: RecallResult, paths: Map<String, String> = emptyMap()) = PhotoSearchModel.tiles(r, paths, ZONE, TODAY)

    @Test fun imageTilesBeforeCaptionTiles() {
        val r = result(image = listOf(hit("i1"), hit("i2")), captions = listOf(hit("c1", PhotoSource.CAPTION)))
        val t = tiles(r, mapOf("i1" to "/p/i1.jpg"))
        assertEquals(listOf("i1", "i2", "c1"), t.map { it.frameId })
        assertEquals(listOf("Image", "Image", "Caption"), t.map { it.badge })
        assertEquals("/p/i1.jpg", t[0].path); assertNull(t[1].path)
    }

    @Test fun scoreFormattedTwoDecimals() {
        assertEquals(listOf("0.50", "0.07", "1.00"), tiles(result(image = listOf(hit("a", score = 0.5), hit("b", score = 0.0712), hit("c", score = 0.999)))).map { it.score })
    }

    @Test fun timeLabelIsClockTodayAndDateOtherwise() {
        val t = tiles(result(image = listOf(hit("a", ts = at(TODAY, 9, 41)), hit("b", ts = at(TODAY.minusDays(2), 18, 5)))))
        assertEquals("09:41", t[0].timeLabel); assertEquals("10 Oct · 18:05", t[1].timeLabel)
        assertEquals("2026-10-12", t[0].date); assertEquals("2026-10-10", t[1].date)
    }

    @Test fun expiredTileHasCaption() {
        val t = tiles(result(captions = listOf(hit("e", PhotoSource.CAPTION, stored = false, described = true, captionId = "cap1", caption = "Keys on a desk")))).single()
        assertTrue(t.expired); assertNull(t.path); assertEquals("Keys on a desk", t.caption); assertEquals("cap1", t.captionMemoryId); assertTrue(t.described)
    }

    /** User ruling 2026-10-22 over S9: the capture time (frame_meta.capturedAtMs) while known, "received HH:mm" only as the fallback. */
    @Test fun expiredTileShowsCaptureTimeElseReceived() {
        val t = tiles(result(image = listOf(
            hit("withCapture", stored = false, ts = at(TODAY, 9, 41), receivedAt = at(TODAY, 14, 2)),
            hit("noCapture", stored = false, ts = null, receivedAt = at(TODAY, 14, 2)),
            hit("neither", stored = false, ts = null, receivedAt = null),
        )))
        assertEquals(listOf("09:41", "received 14:02", ""), t.map { it.timeLabel })
        assertEquals("2026-10-12", t[1].date)
    }

    @Test fun captionsOnlyBannerWithoutImageSpace() {
        assertEquals(PhotoSearchModel.CAPTIONS_ONLY, PhotoSearchModel.banner(result(state = ImageSearchState.NO_IMAGE_SPACE)))
        assertEquals(PhotoSearchModel.CAPTIONS_ONLY, PhotoSearchModel.banner(result(state = ImageSearchState.FAILED)))
        assertNull(PhotoSearchModel.banner(result(state = ImageSearchState.RAN)))
        assertNull(PhotoSearchModel.banner(result(state = ImageSearchState.TIMED_OUT)))
        assertEquals("Searching captions only. For photo search, turn on Nomic in Settings → AI → On-device models.", PhotoSearchModel.CAPTIONS_ONLY)
    }

    @Test fun truncatedNote() {
        assertEquals("Searched the 50000 most recently indexed photos", PhotoSearchModel.note(result(image = listOf(hit("a")), scanned = 50_000, truncated = true)))
        assertEquals("Searched the 812 most recently indexed photos", PhotoSearchModel.note(result(image = listOf(hit("a")), scanned = 812, state = ImageSearchState.TIMED_OUT, truncated = true)))
        assertNull(PhotoSearchModel.note(result(image = listOf(hit("a")))))
    }

    @Test fun noMatchNote() {
        assertEquals("No photos match", PhotoSearchModel.note(result()))
        assertEquals("No photos match · Searched the 3 most recently indexed photos", PhotoSearchModel.note(result(scanned = 3, truncated = true)))
    }

    @Test fun describeStatusTexts() {
        assertEquals("Described · 3 memories saved", PhotoSearchModel.status(DescribeResult.Saved(3, 1)))
        assertEquals("Described · 1 memory saved", PhotoSearchModel.status(DescribeResult.Saved(1, 1)))
        assertEquals("Photo expired", PhotoSearchModel.status(DescribeResult.Expired))
        assertEquals("Already being described — check back in a minute", PhotoSearchModel.status(DescribeResult.Busy))
        assertEquals("AI unavailable — try again later", PhotoSearchModel.status(DescribeResult.Unavailable))
        assertEquals("Memory was cleared", PhotoSearchModel.status(DescribeResult.Wiped))
    }

    @Test fun clusterAndDescribedCarried() {
        val t = tiles(result(image = listOf(hit("a", cluster = "c9", described = true)))).single()
        assertEquals("c9", t.clusterId); assertTrue(t.described); assertFalse(t.expired)
    }
}
