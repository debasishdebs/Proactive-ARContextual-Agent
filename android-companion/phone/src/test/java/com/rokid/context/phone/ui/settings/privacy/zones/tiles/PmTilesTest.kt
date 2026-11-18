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

    // ---- hostile archives (generated in-test) ----
    private fun varintBytes(vararg v: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        for (x0 in v) { var x = x0; while (x >= 0x80 || x < 0) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
        return o.toByteArray()
    }
    private fun corrupt(block: () -> Unit) {
        try { block(); fail("expected CORRUPT") } catch (e: PmTilesException) { assertEquals(PmTilesException.Reason.CORRUPT, e.reason) }
    }
    @Test fun gzipBombDirectoryIsRefusedWithoutAllocatingPastTheCaps() {
        // 32 MiB of zeros: a few KiB gzipped (the Codex repro shape)
        val bomb = F.gzip(ByteArray(32 shl 20))
        assertTrue(bomb.size < 64 * 1024)
        val t0 = System.nanoTime()
        corrupt { open(withBombRoot(bomb)) }
        assertTrue("fails fast", (System.nanoTime() - t0) / 1_000_000 < 5_000)
        corrupt { PmTiles.inflate(bomb, Compression.GZIP, 4 shl 20) }
        corrupt { PmTiles.inflate(bomb, Compression.GZIP, 32 shl 20, maxRatio = 100) } // the ratio cap alone also stops it
    }
    private fun withBombRoot(gz: ByteArray): ByteArray {
        val h = java.nio.ByteBuffer.wrap(F.build(F.sample()).copyOf(127)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.putLong(16, gz.size.toLong()); h.putLong(24, 127L + gz.size); h.putLong(32, 0); h.putLong(40, 127L + gz.size); h.putLong(48, 0); h.putLong(56, 127L + gz.size); h.putLong(64, 0)
        return h.array() + gz
    }

    @Test fun anEntryCountLieAllocatesNothing() {
        var allocated = 0L
        // claims 30,000 entries in a 40,000 byte body of zeros: passes count <= bytes but not bytes/4 plausibility once the body is short
        corrupt { PmTiles.parseDirectory(varintBytes(40_000L) + ByteArray(100), onAlloc = { allocated += it }) }
        // count within the plausibility bound but the columns run out
        corrupt { PmTiles.parseDirectory(varintBytes(10L) + ByteArray(39), onAlloc = { allocated += it }) }
        // over the entry cap even though the bytes would carry it
        corrupt { PmTiles.parseDirectory(varintBytes(100_001L) + ByteArray(500_000), onAlloc = { allocated += it }) }
        assertEquals("no array was requested", 0L, allocated)
    }

    @Test fun aValidMaxSizeDirectoryAllocatesWithinTheCap() {
        var allocated = 0L
        val n = 1000
        val rows = (0 until n).map { longArrayOf(it.toLong(), it * 10L, 10, 1) }
        val parsed = PmTiles.parseDirectory(F.directory(rows), onAlloc = { allocated += it })
        assertEquals(n, parsed.size)
        assertTrue(allocated <= n * 32L)
    }

    @Test fun hugeVarintsAndOverflowingOffsetsAreCorrupt() {
        corrupt { PmTiles.parseDirectory(ByteArray(12) { 0xFF.toByte() }) }
        // one entry: id, run, len, then an offset that is "same as previous end" chained from Long.MAX_VALUE-ish offsets
        corrupt { PmTiles.parseDirectory(varintBytes(2L, Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE / 2 + 1, 1, 1, 1, 1, 0x7FFFFFFFL, 1, 1)) }
        corrupt { PmTiles.parseDirectory(varintBytes(1L, 1, 1, 1, 1) + byteArrayOf(9)) } // trailing junk
    }

    @Test fun headerOffsetsBeyondTheFileOrOverflowingAreRefused() {
        val good = F.build(F.sample())
        for (field in listOf(8, 16, 24, 32, 40, 48, 56, 64)) {
            for (v in listOf(good.size.toLong() + 1, Long.MAX_VALUE, -1L)) {
                val b = java.nio.ByteBuffer.wrap(good.copyOf()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                b.putLong(field, v)
                val bytes = b.array()
                corrupt { open(bytes) }
            }
        }
    }

    @Test fun aLeafThatPointsAtItselfStopsAsCorrupt() {
        val tileId = PmTiles.tileId(0, 0, 0)
        // a leaf directory whose only entry is a leaf pointer to offset 0 with the leaf's own gzipped length (fixed point of the length)
        var len = 1L
        var body = F.gzip(F.directory(listOf(longArrayOf(tileId, 0, len, 0))))
        repeat(8) { len = body.size.toLong(); body = F.gzip(F.directory(listOf(longArrayOf(tileId, 0, len, 0)))) }
        assertEquals(len, body.size.toLong())
        val root = F.gzip(F.directory(listOf(longArrayOf(tileId, 0, body.size.toLong(), 0))))
        val h = java.nio.ByteBuffer.wrap(F.build(F.sample()).copyOf(127)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.putLong(16, root.size.toLong()); h.putLong(24, 127L + root.size); h.putLong(32, 0)
        h.putLong(40, 127L + root.size); h.putLong(48, body.size.toLong()); h.putLong(56, 127L + root.size + body.size); h.putLong(64, 0)
        val r = open(h.array() + root + body)
        corrupt { r.tile(0, 0, 0) }
    }

    @Test fun anOversizedTileIsRefusedBothCompressedAndInflated() {
        val big = ByteArray(PmTilesReader.MAX_TILE_BYTES + 1)
        val r = open(F.build(listOf(F.Tile(0, 0, 0, big))))
        corrupt { r.tile(0, 0, 0) }
        val bomb = ByteArray(PmTilesReader.MAX_TILE_BYTES + 1)
        val r2 = open(F.build(listOf(F.Tile(0, 0, 0, bomb)), tileGzip = true)) // compressed is tiny, inflated is over the cap
        corrupt { r2.tile(0, 0, 0) }
    }

    @Test fun aTinyValidFileStillWorks() {
        val r = open(F.build(F.sample()))
        assertArrayEquals(F.png(0), r.tile(0, 0, 0))
        assertArrayEquals(F.png(31), r.tile(2, 3, 1))
    }

    @Test fun decodedPixelsAreCappedBySubsampling() {
        assertEquals(1, TileDecode.sampleSize(256, 256))
        assertEquals(2, TileDecode.sampleSize(5_000, 4_000)) // 20 MP -> 5 MP
        assertEquals(0, TileDecode.sampleSize(0, 10))
        assertEquals(0, TileDecode.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun theTileCacheIsBoundedByBytesAndSurvivesOutOfMemory() {
        val cache = TileCache<ByteArray>(capacity = 100, maxBytes = 1_000, weigh = { it.size.toLong() }) { k -> if (k.x == 99) throw OutOfMemoryError("test") else ByteArray(400) }
        cache.ensure((0 until 20).map { TileKey(1, it, 0) } + TileKey(1, 99, 0))
        assertTrue(cache.heldBytes() <= 1_000)
        assertEquals(1, cache.problems)
    }
}
