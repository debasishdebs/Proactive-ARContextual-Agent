package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.settings.privacy.zones.MapViewport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.PmTilesFixture as F

/**
 * Hostile-input and property tests for the offline PMTiles reader and the tile maths, JVM only. Everything random uses a fixed seed
 * ([SEED]) so a failure reproduces: the failing message names the base archive, the iteration and the mutation.
 * Already covered by PmTilesTest / TileMathTest (not repeated here): bad magic and short files, versions/compression/tile types, header offsets past EOF,
 * gzip bomb, entry-count lie, varint overflow and trailing junk, self-pointing leaf, oversized tiles, TileCache byte bound + OOM, sampleSize basics.
 */
class PmTilesHostileTest {
    private companion object { const val SEED = 0x5EED_2026L }

    private fun open(b: ByteArray) = PmTilesReader.open(ArrayByteSource(b))
    private fun corrupt(why: PmTilesException.Reason = PmTilesException.Reason.CORRUPT, block: () -> Unit) {
        try { block(); fail("expected $why") } catch (e: PmTilesException) { assertEquals(why, e.reason) }
    }
    private fun varint(o: ByteArrayOutputStream, v: Long) { var x = v; while (x >= 0x80 || x < 0) { o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun rawDir(vararg rows: LongArray) = F.directory(rows.toList())

    /** A header copied from a good archive with the section table and zoom range replaced; directories and data are written uncompressed. */
    private fun archive(root: ByteArray, leaf: ByteArray = ByteArray(0), data: ByteArray = ByteArray(0), metadata: ByteArray = ByteArray(0), minZ: Int = 0, maxZ: Int = 2): ByteArray {
        val h = ByteBuffer.wrap(F.build(F.sample(), dirGzip = false).copyOf(127)).order(ByteOrder.LITTLE_ENDIAN)
        val rootOff = 127L; val metaOff = rootOff + root.size; val leafOff = metaOff + metadata.size; val dataOff = leafOff + leaf.size
        h.putLong(8, rootOff); h.putLong(16, root.size.toLong()); h.putLong(24, metaOff); h.putLong(32, metadata.size.toLong())
        h.putLong(40, leafOff); h.putLong(48, leaf.size.toLong()); h.putLong(56, dataOff); h.putLong(64, data.size.toLong())
        h.put(97, 1); h.put(98, 1); h.put(100, minZ.toByte()); h.put(101, maxZ.toByte())
        return h.array() + root + metadata + leaf + data
    }

    /** Only the documented type may come out of the reader; anything else (and any Error) is a bug. */
    private inline fun <T> documented(what: String, block: () -> T): T? =
        try { block() } catch (e: PmTilesException) { null } catch (e: Throwable) { throw AssertionError("$what threw ${e.javaClass.name}: ${e.message}", e) }

    private fun exercise(bytes: ByteArray, rnd: Random, what: String) {
        val r = documented(what) { open(bytes) } ?: return
        documented("$what attribution") { r.attribution() }
        for (z in r.minZoom..r.maxZoom.coerceAtMost(PmHeader.MAX_ZOOM)) {
            val n = 1L shl z
            val probes = if (z <= 2) (0 until n.toInt()).flatMap { x -> (0 until n.toInt()).map { y -> x to y } } else List(6) { rnd.nextLong(n).toInt() to rnd.nextLong(n).toInt() }
            for ((x, y) in probes) documented("$what tile($z,$x,$y)") { r.tile(z, x, y)?.size }
        }
    }

    // ---- fuzz: mutated real archives ----
    @Test fun mutatedArchivesOnlyEverRaiseThePmTilesException() {
        val bases = mapOf(
            "gz-root" to F.build(F.sample()),
            "leaf" to F.build(F.sample(), leaf = true),
            "raw-gzip-tiles" to F.build(F.sample(), dirGzip = false, tileGzip = true),
            "runs" to F.build(listOf(F.Tile(2, 0, 0, F.png(7), run = 3), F.Tile(1, 1, 1, F.png(9)))),
        )
        val interesting = longArrayOf(0, 1, -1, Long.MAX_VALUE, Int.MAX_VALUE.toLong(), 1L shl 31, 1L shl 32, (4L shl 20), (4L shl 20) + 1, (2L shl 20) + 1, 126, 127, 128)
        val rnd = Random(SEED)
        val t0 = System.nanoTime()
        for ((name, base) in bases) repeat(500) { i ->
            val b = base.copyOf()
            val what = "$name#$i"
            when (rnd.nextInt(4)) {
                0 -> repeat(rnd.nextInt(1, 9)) { val at = if (rnd.nextBoolean()) rnd.nextInt(127) else rnd.nextInt(b.size); b[at] = rnd.nextInt(256).toByte() }
                1 -> { val f = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64)[rnd.nextInt(8)]; ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(f, interesting[rnd.nextInt(interesting.size)]) }
                2 -> { exercise(b.copyOf(rnd.nextInt(b.size + 1)), rnd, "$what truncated"); return@repeat }
                else -> { val at = 127 + rnd.nextInt(b.size - 127); b[at] = (b[at].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            }
            exercise(b, rnd, what)
        }
        assertTrue("bounded time", (System.nanoTime() - t0) / 1_000_000 < 60_000)
    }

    @Test fun everyPrefixOfAGoodArchiveIsRefusedOrReadable() {
        for (base in listOf(F.build(F.sample()), F.build(F.sample(), leaf = true))) {
            for (len in 0..base.size) exercise(base.copyOf(len), Random(len.toLong()), "prefix $len")
            for (len in 0 until 127) corrupt(PmTilesException.Reason.NOT_PMTILES) { open(base.copyOf(len)) }
        }
    }

    @Test fun randomBytesAfterAValidMagicAndVersionAreRefusedCleanly() {
        val rnd = Random(SEED + 1)
        repeat(2_000) { i ->
            val b = rnd.nextBytes(rnd.nextInt(127, 600))
            "PMTiles".toByteArray().copyInto(b); b[7] = 3
            exercise(b, rnd, "random#$i")
        }
    }

    @Test fun headerZoomAndRootLengthExtremesAreCorruptNotACrash() {
        val good = F.build(F.sample())
        fun with(edit: (ByteBuffer) -> Unit) = ByteBuffer.wrap(good.copyOf()).order(ByteOrder.LITTLE_ENDIAN).also(edit).array()
        corrupt { open(with { it.put(101, 27) }) } // max zoom over 26
        corrupt { open(with { it.put(101, 0xFF.toByte()) }) }
        corrupt { open(with { it.put(100, 5); it.put(101, 3) }) } // min > max
        corrupt { open(with { it.putLong(16, 0) }) } // empty root
        corrupt { open(with { it.putLong(16, (4L shl 20) + 1) }) } // root over the 4 MiB cap (and past EOF)
        corrupt(PmTilesException.Reason.UNSUPPORTED_COMPRESSION) { open(with { it.put(97, 0) }) } // unknown
        corrupt(PmTilesException.Reason.UNSUPPORTED_COMPRESSION) { open(with { it.put(98, 0xC8.toByte()) }) }
    }

    // ---- directories and leaves ----
    @Test fun leafChainsAreFollowedThroughThreeLevelsAndRefusedBeyond() {
        val tile = F.png(5)
        val id = PmTiles.tileId(0, 0, 0)
        fun chain(levels: Int): ByteArray {
            // build bottom-up: the deepest leaf holds the tile, every level above is one pointer; leaf offsets are relative to the leaf section
            var leaves = ByteArray(0)
            var below = rawDir(longArrayOf(id, 0, tile.size.toLong(), 1))
            for (l in 0 until levels) {
                val at = leaves.size.toLong(); leaves += below
                below = rawDir(longArrayOf(id, at, below.size.toLong(), 0))
            }
            return archive(root = below, leaf = leaves, data = tile, minZ = 0, maxZ = 0)
        }
        assertArrayEquals(tile, open(chain(1)).tile(0, 0, 0))
        assertArrayEquals(tile, open(chain(3)).tile(0, 0, 0))
        corrupt { open(chain(4)).tile(0, 0, 0) }
        corrupt { open(chain(40)).tile(0, 0, 0) }
    }

    @Test fun aTwoLeafCycleStopsAsCorrupt() {
        val id = PmTiles.tileId(0, 0, 0)
        val size = rawDir(longArrayOf(id, 0, 1, 0)).size
        val b = rawDir(longArrayOf(id, 0, size.toLong(), 0)) // B -> A (offset 0)
        val a = rawDir(longArrayOf(id, size.toLong(), size.toLong(), 0)) // A -> B (offset size)
        require(a.size == size && b.size == size)
        val root = rawDir(longArrayOf(id, 0, size.toLong(), 0))
        val r = open(archive(root = root, leaf = a + b))
        corrupt { r.tile(0, 0, 0) }
    }

    @Test fun leafPointersOutsideTheLeafSectionOrOfAbsurdSizeAreCorrupt() {
        val id = PmTiles.tileId(0, 0, 0)
        val leaf = rawDir(longArrayOf(id, 0, 1, 1))
        for ((off, len) in listOf(0L to 0L, 0L to (leaf.size + 1).toLong(), (leaf.size).toLong() to 1L, 1L to leaf.size.toLong(), 0L to (4L shl 20) + 1, Long.MAX_VALUE / 2 to 5L, 0L to Int.MAX_VALUE.toLong())) {
            val r = open(archive(root = rawDir(longArrayOf(id, off, len, 0)), leaf = leaf, data = F.png(1), maxZ = 0))
            corrupt { r.tile(0, 0, 0) }
        }
    }

    @Test fun zeroLengthAndOutOfDataTilesAreCorruptNotEmptyImages() {
        val id = PmTiles.tileId(0, 0, 0)
        for ((off, len) in listOf(0L to 0L, 0L to 6L, 5L to 1L, (1L shl 40) to 1L, 0L to Int.MAX_VALUE.toLong())) {
            val r = open(archive(root = rawDir(longArrayOf(id, off, len, 1)), data = F.png(1), maxZ = 0))
            corrupt { r.tile(0, 0, 0) }
        }
        corrupt { open(archive(root = rawDir(longArrayOf(id, 0, 5, 1)), data = ByteArray(0), maxZ = 0)).tile(0, 0, 0) } // empty data section
    }

    @Test fun runLengthsPastIntRangeAreCoveredWithoutOverflow() {
        val o = ByteArrayOutputStream()
        varint(o, 2); varint(o, 5); varint(o, 10) // ids 5, 15
        varint(o, 1L shl 40); varint(o, Int.MAX_VALUE.toLong()) // runs far beyond Int
        varint(o, 3); varint(o, 3); varint(o, 1); varint(o, 0)
        val es = PmTiles.parseDirectory(o.toByteArray())
        assertEquals(Int.MAX_VALUE, es[0].runLength)
        assertNotNull(PmTiles.find(es, 14)); assertNotNull(PmTiles.find(es, 5L + Int.MAX_VALUE - 1))
        assertNull(PmTiles.find(es, 4))
        // ids at the top of the Long range: find must not overflow
        val top = listOf(DirEntry(Long.MAX_VALUE - 1, 0, 1, Int.MAX_VALUE), DirEntry(Long.MAX_VALUE, 1, 1, 1))
        assertNotNull(PmTiles.find(top, Long.MAX_VALUE)); assertNull(PmTiles.find(top, 0))
        assertNull(PmTiles.find(emptyList(), 0))
    }

    @Test fun idDeltasThatOverflowTheLongAreCorrupt() {
        val o = ByteArrayOutputStream()
        varint(o, 2); varint(o, Long.MAX_VALUE); varint(o, 1) // ids: MAX, then MAX + 1 overflows
        for (c in 0 until 3) { varint(o, 1); varint(o, 1) }
        corrupt { PmTiles.parseDirectory(o.toByteArray()) }
    }

    @Test fun aDirectoryAtTheEntryCapParsesAndOneOverIsRefusedFast() {
        val rows = (0 until PmTiles.MAX_ENTRIES).map { longArrayOf(it.toLong(), it * 3L, 3, 1) }
        val t0 = System.nanoTime()
        assertEquals(PmTiles.MAX_ENTRIES, PmTiles.parseDirectory(F.directory(rows)).size)
        val over = (0..PmTiles.MAX_ENTRIES).map { longArrayOf(it.toLong(), it * 3L, 3, 1) }
        corrupt { PmTiles.parseDirectory(F.directory(over)) }
        assertTrue("bounded time", (System.nanoTime() - t0) / 1_000_000 < 15_000)
    }

    @Test fun randomDirectoriesRoundTripAndFindAgreesWithABruteForceOracle() {
        val rnd = Random(SEED + 2)
        repeat(300) { iter ->
            val n = rnd.nextInt(0, 60)
            var id = rnd.nextLong(0, 50); var off = 0L
            val rows = ArrayList<LongArray>()
            repeat(n) {
                val len = rnd.nextLong(1, 500); val run = if (rnd.nextInt(5) == 0) 0L else rnd.nextLong(1, 6)
                rows += longArrayOf(id, if (rnd.nextBoolean()) off else off + rnd.nextLong(0, 9), len, run)
                off = rows.last()[1] + len; id += rnd.nextLong(1, 12)
            }
            val es = PmTiles.parseDirectory(F.directory(rows))
            assertEquals("iter $iter", n, es.size)
            for ((i, r) in rows.withIndex()) { assertEquals(r[0], es[i].tileId); assertEquals(r[1], es[i].offset); assertEquals(r[2].toInt(), es[i].length); assertEquals(r[3].toInt(), es[i].runLength) }
            for (probe in 0L..(id + 8)) {
                val last = es.lastOrNull { it.tileId <= probe }
                val want = when { last == null -> null; last.tileId == probe -> last; last.runLength == 0 -> last; probe - last.tileId < last.runLength -> last; else -> null }
                assertTrue("iter $iter probe $probe", PmTiles.find(es, probe) === want)
            }
        }
    }

    @Test fun parsingArbitraryBytesNeverEscapesTheDocumentedException() {
        val rnd = Random(SEED + 3)
        repeat(5_000) { i ->
            val b = rnd.nextBytes(rnd.nextInt(0, 80))
            if (i % 3 == 0 && b.isNotEmpty()) b[0] = rnd.nextInt(0, 6).toByte() // small entry counts reach the column walk
            documented("dir#$i") { PmTiles.parseDirectory(b, onAlloc = { assertTrue("alloc bounded", it <= 40L * 80) }) }
            documented("inflate#$i") { PmTiles.inflate(b, Compression.GZIP, 1 shl 20, 1000) }
            documented("inflate-none#$i") { PmTiles.inflate(b, Compression.NONE, 16) }
        }
    }

    // ---- hostile metadata ----
    @Test fun hostileMetadataYieldsNullAttributionNeverACrash() {
        val deep = ("[".repeat(1 shl 19) + "]".repeat(1 shl 19)).toByteArray() // half a million levels, inside the 1 MiB metadata cap
        val cases = mapOf(
            "deep" to deep, "deep object" to ("{\"a\":".repeat(100_000) + "1" + "}".repeat(100_000)).toByteArray(),
            "not json" to byteArrayOf(0, 1, 2, 3, 0xFF.toByte()), "invalid utf8" to byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D), "array" to "[1,2]".toByteArray(),
            "wrong type" to """{"attribution":12}""".toByteArray(), "object value" to """{"attribution":{"a":1}}""".toByteArray(),
            "blank" to """{"attribution":"<b></b>  "}""".toByteArray(), "dup keys" to """{"attribution":"a","attribution":"b"}""".toByteArray(),
            "control chars" to "{\"attribution\":\"x\\u0000\\u0007y\"}".toByteArray(),
        )
        for ((name, meta) in cases) {
            val r = open(archive(root = rawDir(longArrayOf(0, 0, 1, 1)), data = F.png(1), metadata = meta, maxZ = 0))
            val got = try { r.attribution() } catch (e: Throwable) { throw AssertionError("$name: ${e.javaClass.name}", e) }
            if (name in setOf("deep", "deep object", "not json", "invalid utf8", "array", "wrong type", "object value", "blank")) assertNull(name, got)
        }
        val long = """{"attribution":"${"é".repeat(5_000)}"}""".toByteArray()
        assertEquals(120, open(archive(root = rawDir(longArrayOf(0, 0, 1, 1)), data = F.png(1), metadata = long, maxZ = 0)).attribution()!!.length)
    }

    @Test fun metadataLengthOverTheCapOrAGzipBombIsIgnored() {
        val good = F.build(F.sample())
        val over = ByteBuffer.wrap(good.copyOf()).order(ByteOrder.LITTLE_ENDIAN).also { it.putLong(32, (1L shl 20) + 1); it.putLong(24, 127); it.putLong(64, 0) }.array()
        // the metadata section now claims > 1 MiB: either refused at open (past EOF) or attribution() is null
        val r = documented("meta over cap") { open(over) }
        if (r != null) assertNull(r.attribution())
        // a metadata section that inflates far past the 1 MiB cap, behind a gzip internal-compression header
        val bomb = F.gzip(ByteArray(64 shl 20))
        val root = F.gzip(rawDir(longArrayOf(0, 0, 1, 1)))
        val h = ByteBuffer.wrap(archive(root = ByteArray(0), data = F.png(1), maxZ = 0).copyOf(127)).order(ByteOrder.LITTLE_ENDIAN)
        h.putLong(8, 127); h.putLong(16, root.size.toLong()); h.putLong(24, 127L + root.size); h.putLong(32, bomb.size.toLong())
        val leafOff = 127L + root.size + bomb.size
        h.putLong(40, leafOff); h.putLong(48, 0); h.putLong(56, leafOff); h.putLong(64, 5); h.put(97, 2)
        val rb = open(h.array() + root + bomb + F.png(1))
        assertNull(rb.attribution())
    }

    // ---- a lying or failing byte source ----
    @Test fun aSourceThatFailsOrLiesAboutItsSizeIsAUnreadableOrCorruptArchive() {
        val good = F.build(F.sample())
        class Src(val claimed: Long, val failAfter: Int) : ByteSource {
            var reads = 0; var closed = false
            override val size get() = claimed
            override fun read(offset: Long, length: Int): ByteArray {
                if (++reads > failAfter) throw IOException("disk gone")
                if (offset < 0 || offset + length > good.size) throw IOException("past real end")
                return good.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
            override fun close() { closed = true }
        }
        val s1 = Src(good.size.toLong(), 0)
        corrupt(PmTilesException.Reason.UNREADABLE) { PmTilesReader.open(s1) }; assertTrue(s1.closed)
        val s2 = Src(Long.MAX_VALUE, 5) // claims a huge file, only the real bytes exist
        val r = documented("lying size") { PmTilesReader.open(s2) }
        if (r != null) documented("lying size tile") { r.tile(1, 0, 0) }
        val s3 = Src(good.size.toLong(), 1) // header reads, the root read fails
        corrupt(PmTilesException.Reason.UNREADABLE) { PmTilesReader.open(s3) }; assertTrue(s3.closed)
    }

    // ---- TileMath and ids ----
    @Test fun tileIdsAreABijectionPerZoomAndOrderedAcrossZooms() {
        var base = 0L
        for (z in 0..5) {
            val n = 1 shl z
            val ids = (0 until n).flatMap { x -> (0 until n).map { y -> PmTiles.tileId(z, x, y) } }
            assertEquals((base until base + n.toLong() * n).toList(), ids.sorted())
            base += n.toLong() * n
        }
    }

    @Test fun tileIdsStayInTheirZoomBandUpToTheMaxZoomAndRefuseOutOfRange() {
        val rnd = Random(SEED + 4)
        var band = 0L
        for (z in 0..PmHeader.MAX_ZOOM) {
            val n = 1L shl z
            repeat(50) {
                val x = if (it == 0) 0 else if (it == 1) (n - 1).toInt() else rnd.nextLong(n).toInt()
                val y = if (it == 0) 0 else if (it == 1) (n - 1).toInt() else rnd.nextLong(n).toInt()
                val id = PmTiles.tileId(z, x, y)
                assertTrue("z$z", id >= band && id < band + n * n)
            }
            band += n * n
        }
        for ((z, x, y) in listOf(Triple(-1, 0, 0), Triple(27, 0, 0), Triple(Int.MAX_VALUE, 0, 0), Triple(3, -1, 0), Triple(3, 0, -1), Triple(3, 8, 0), Triple(3, 0, 8), Triple(0, 1, 0), Triple(0, Int.MIN_VALUE, Int.MAX_VALUE)))
            try { PmTiles.tileId(z, x, y); fail("$z/$x/$y") } catch (e: IllegalArgumentException) { /* the documented refusal for a caller's bad coordinate */ }
    }

    @Test fun aReaderAskedForAnOutOfRangeTileSaysSoWithoutTouchingTheFile() {
        val r = open(F.build(F.sample()))
        assertNull(r.tile(-5, 0, 0)); assertNull(r.tile(Int.MAX_VALUE, 0, 0)); assertNull(r.tile(3, 0, 0)) // outside the zoom range: absent
        try { r.tile(1, 2, 0); fail() } catch (e: IllegalArgumentException) { /* x outside the zoom is the caller's bug, not the archive's */ }
    }

    @Test fun zoomForNeverThrowsAndStaysInsideTheArchiveForAnyPpd() {
        val rnd = Random(SEED + 5)
        val specials = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, -1.0, 1e-300, 1e300, Double.MIN_VALUE, Double.MAX_VALUE)
        repeat(1_000) {
            val ppd = if (it < specials.size) specials[it] else Math.pow(10.0, rnd.nextDouble(-8.0, 12.0)) * (if (rnd.nextInt(8) == 0) -1 else 1)
            val lo = rnd.nextInt(0, 14); val hi = rnd.nextInt(lo, PmHeader.MAX_ZOOM + 1)
            val z = TileMath.zoomFor(ppd, lo, hi)
            assertTrue("ppd=$ppd", z in lo..hi)
        }
    }

    @Test fun visibleAndScreenRectSurviveNonFiniteAndExtremeViewports() {
        val rnd = Random(SEED + 6)
        val nums = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0, -90.0, 90.0, 1e9, -1e9, 180.0, -180.0, 1e-9)
        val sizes = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f, 1f, 800f, 1e9f, Float.MAX_VALUE)
        repeat(3_000) {
            fun d() = if (rnd.nextBoolean()) nums[rnd.nextInt(nums.size)] else rnd.nextDouble(-400.0, 400.0)
            val vp = MapViewport(d(), d(), if (rnd.nextBoolean()) nums[rnd.nextInt(nums.size)] else TileMath.ppdAt(rnd.nextInt(0, 27)), sizes[rnd.nextInt(sizes.size)], sizes[rnd.nextInt(sizes.size)])
            val z = rnd.nextInt(0, PmHeader.MAX_ZOOM + 1)
            val cap = if (rnd.nextBoolean()) TileMath.MAX_VISIBLE else rnd.nextInt(-5, 5_000)
            val keys = try { TileMath.visible(vp, z, cap) } catch (e: Throwable) { throw AssertionError("visible($vp, $z, $cap): ${e.javaClass.name}", e) }
            assertTrue(keys.size <= maxOf(cap, 0))
            val n = 1L shl z
            for (k in keys) { assertTrue(k.x in 0 until n && k.y in 0 until n && k.z == z); PmTiles.tileId(k.z, k.x, k.y) /* a visible key is always a legal id */ }
            if (keys.isNotEmpty()) TileMath.screenRect(vp, keys.first())
        }
        for (v in nums) { TileMath.latOf(v, 5); TileMath.lonOf(v, 5); TileMath.ppdAt(0) }
    }

    @Test fun sampleSizeIsEitherUnusableOrKeepsDecodedPixelsUnderTheCap() {
        val rnd = Random(SEED + 7)
        repeat(5_000) {
            val w = if (rnd.nextInt(6) == 0) rnd.nextInt() else rnd.nextInt(0, 40_000); val h = if (rnd.nextInt(6) == 0) rnd.nextInt() else rnd.nextInt(0, 40_000)
            val s = TileDecode.sampleSize(w, h)
            if (w <= 0 || h <= 0) assertEquals(0, s)
            else if (s != 0) { assertTrue(s >= 1 && (s and (s - 1)) == 0 && s <= 64); assertTrue(w.toLong() / s * (h.toLong() / s) <= TileDecode.MAX_PIXELS) }
            else assertTrue("refused only when 1/64 is not enough", w.toLong() / 64 * (h.toLong() / 64) > TileDecode.MAX_PIXELS)
        }
    }
}
