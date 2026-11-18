package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.PmTilesFixture as F

class PmTilesTest {
    private fun open(b: ByteArray) = PmTilesReader.open(ArrayByteSource(b))
    private fun refused(b: ByteArray, why: PmTilesException.Reason) {
        try { open(b).close(); fail("expected $why") } catch (e: PmTilesException) { assertEquals(why, e.reason) }
    }

    @Test fun hilbertTileIdsMatchTheSpec() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0)); assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1)); assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        // the 16 tiles of z2 are a permutation of 5..20 and neighbours along the curve are adjacent tiles
        val ids = (0..3).flatMap { x -> (0..3).map { y -> PmTiles.tileId(2, x, y) } }
        assertEquals((5L..20L).toList(), ids.sorted())
        val byId = (0..3).flatMap { x -> (0..3).map { y -> PmTiles.tileId(2, x, y) to (x to y) } }.sortedBy { it.first }.map { it.second }
        for (i in 1 until byId.size) assertEquals(1, Math.abs(byId[i].first - byId[i - 1].first) + Math.abs(byId[i].second - byId[i - 1].second))
        assertEquals(21L, PmTiles.tileId(3, 0, 0))
    }

    @Test fun readsTilesFromAGzippedRootDirectory() {
        val r = open(F.build(F.sample()))
        assertEquals(0, r.minZoom); assertEquals(2, r.maxZoom)
        assertArrayEquals(F.png(11), r.tile(1, 0, 1))
        assertArrayEquals(F.png(31), r.tile(2, 3, 1))
        assertArrayEquals(F.png(0), r.tile(0, 0, 0))
        assertNull(r.tile(2, 1, 1)) // inside the zoom range but not stored
        assertNull(r.tile(3, 0, 0)) // beyond the archive's max zoom
    }

    @Test fun readsUncompressedDirectoriesAndGzippedTiles() {
        val r = open(F.build(F.sample(), dirGzip = false, tileGzip = true))
        assertArrayEquals(F.png(20), r.tile(2, 0, 0))
        assertArrayEquals(F.png(13), r.tile(1, 1, 1))
    }

    @Test fun followsALeafDirectory() {
        val r = open(F.build(F.sample(), leaf = true))
        assertArrayEquals(F.png(10), r.tile(1, 0, 0))
        assertArrayEquals(F.png(31), r.tile(2, 3, 1))
        assertNull(r.tile(2, 2, 2))
    }

    @Test fun aRunLengthRepeatsOneTileOverConsecutiveIds() {
        val first = PmTiles.tileId(2, 0, 0)
        val r = open(F.build(listOf(F.Tile(2, 0, 0, F.png(7), run = 3))))
        assertArrayEquals(F.png(7), r.tile(2, 0, 0))
        // ids first+1 and first+2 are covered by the run; first+3 is not
        val covered = (0..3).flatMap { x -> (0..3).map { y -> Triple(x, y, PmTiles.tileId(2, x, y)) } }
        for ((x, y, id) in covered) {
            if (id in first..first + 2) assertArrayEquals(F.png(7), r.tile(2, x, y)) else assertNull(r.tile(2, x, y))
        }
    }

    @Test fun attributionIsReadFromMetadataAndStrippedOfMarkup() {
        val r = open(F.build(F.sample(), metadata = """{"attribution":"<a href='x'>© OpenStreetMap</a> contributors"}"""))
        assertEquals("© OpenStreetMap contributors", r.attribution())
        assertNull(open(F.build(F.sample())).attribution())
    }

    @Test fun notPmtilesAndTooSmallAreRefused() {
        refused(ByteArray(10), PmTilesException.Reason.NOT_PMTILES)
        refused(ByteArray(300), PmTilesException.Reason.NOT_PMTILES)
        refused("%PDF-1.4".toByteArray().copyOf(200), PmTilesException.Reason.NOT_PMTILES)
    }

    @Test fun otherVersionsVectorTilesAndUnsupportedCompressionAreRefusedInWords() {
        refused(F.build(F.sample(), version = 2), PmTilesException.Reason.UNSUPPORTED_VERSION)
        refused(F.build(F.sample(), type = 1), PmTilesException.Reason.VECTOR_TILES)
        refused(F.build(F.sample(), type = 5), PmTilesException.Reason.UNSUPPORTED_TILE_TYPE)
        refused(F.build(F.sample(), tileCode = 3), PmTilesException.Reason.UNSUPPORTED_COMPRESSION) // brotli
        refused(F.build(F.sample(), internalCode = 4), PmTilesException.Reason.UNSUPPORTED_COMPRESSION) // zstd
        assertTrue(PmTilesException.Reason.entries.all { it.words.endsWith(".") })
    }

    @Test fun aTruncatedFileAndABrokenDirectoryAreCorrupt() {
        val good = F.build(F.sample())
        refused(good.copyOf(100), PmTilesException.Reason.NOT_PMTILES)
        refused(good.copyOf(good.size - 3), PmTilesException.Reason.CORRUPT) // data section runs past the end
        val bad = good.copyOf()
        for (i in 127 until 137) bad[i] = 0x55 // the gzip root directory
        refused(bad, PmTilesException.Reason.CORRUPT)
    }

    @Test fun aTileThatPointsPastTheDataIsCorruptNotACrash() {
        val r = open(F.build(listOf(F.Tile(0, 0, 0, F.png(1))), badLength = 9_999))
        try { r.tile(0, 0, 0); fail() } catch (e: PmTilesException) { assertEquals(PmTilesException.Reason.CORRUPT, e.reason) }
    }

    @Test fun anInflatedDirectoryOverTheLimitIsRefused() {
        val big = F.gzip(ByteArray(2 shl 20))
        try { PmTiles.inflate(big, Compression.GZIP, 1 shl 20); fail() } catch (e: PmTilesException) { assertEquals(PmTilesException.Reason.CORRUPT, e.reason) }
        assertEquals(2 shl 20, PmTiles.inflate(big, Compression.GZIP, 4 shl 20).size)
    }

    @Test fun directoryParsingRejectsTruncatedAndAbsurdInput() {
        try { PmTiles.parseDirectory(byteArrayOf(0x7F)); fail() } catch (e: PmTilesException) { assertEquals(PmTilesException.Reason.CORRUPT, e.reason) } // 127 entries, no bytes
        try { PmTiles.parseDirectory(ByteArray(11) { 0xFF.toByte() }); fail() } catch (e: PmTilesException) { assertEquals(PmTilesException.Reason.CORRUPT, e.reason) }
        assertEquals(0, PmTiles.parseDirectory(byteArrayOf(0)).size)
    }
}
