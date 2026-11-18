package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * A pure-Kotlin PMTiles v3 reader for RASTER archives (PNG / JPEG / WebP tiles). No library, no network: it only reads bytes it is given.
 * Layout: a 127-byte header, a root directory, optional leaf directories, tile data. Directories are varint-packed (optionally gzip). Tile ids run along a Hilbert curve.
 * Anything it cannot read exactly (vector tiles, brotli/zstd, a bad offset) is refused with a [PmTilesException]: the map then falls back to the plain canvas.
 */
class PmTilesException(val reason: Reason) : IOException(reason.name) {
    enum class Reason(val words: String) {
        NOT_PMTILES("That file is not a PMTiles archive."),
        UNSUPPORTED_VERSION("Only PMTiles version 3 is supported."),
        UNSUPPORTED_COMPRESSION("That archive uses a compression the app cannot read (only none and gzip)."),
        VECTOR_TILES("That archive holds vector tiles. Only raster (PNG, JPEG or WebP) tiles are supported."),
        UNSUPPORTED_TILE_TYPE("That archive holds a tile format the app cannot draw."),
        CORRUPT("That file is damaged or cut short."),
        UNREADABLE("That file could not be read."),
    }
}

/** Random-access, read-only bytes. [read] returns exactly [length] bytes or throws; never returns a short array. */
interface ByteSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class ArrayByteSource(private val data: ByteArray) : ByteSource {
    override val size: Long get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > data.size) throw PmTilesException(PmTilesException.Reason.CORRUPT)
        return data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
    override fun close() {}
}

enum class Compression(val code: Int) { UNKNOWN(0), NONE(1), GZIP(2), BROTLI(3), ZSTD(4) }

enum class TileType(val code: Int) { UNKNOWN(0), MVT(1), PNG(2), JPEG(3), WEBP(4), AVIF(5) }

data class PmHeader(
    val rootOffset: Long, val rootLength: Long, val metadataOffset: Long, val metadataLength: Long,
    val leafOffset: Long, val leafLength: Long, val dataOffset: Long, val dataLength: Long,
    val internalCompression: Compression, val tileCompression: Compression, val tileType: TileType,
    val minZoom: Int, val maxZoom: Int,
) {
    companion object {
        const val SIZE = 127
        private val MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)

        fun parse(b: ByteArray, fileSize: Long): PmHeader {
            if (b.size < SIZE) throw PmTilesException(PmTilesException.Reason.NOT_PMTILES)
            for (i in MAGIC.indices) if (b[i] != MAGIC[i]) throw PmTilesException(PmTilesException.Reason.NOT_PMTILES)
            if ((b[7].toInt() and 0xFF) != 3) throw PmTilesException(PmTilesException.Reason.UNSUPPORTED_VERSION)
            fun u64(at: Int): Long {
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
                if (v < 0) throw PmTilesException(PmTilesException.Reason.CORRUPT)
                return v
            }
            val internal = comp(b[97].toInt() and 0xFF)
            val tile = comp(b[98].toInt() and 0xFF)
            val type = TileType.entries.firstOrNull { it.code == (b[99].toInt() and 0xFF) } ?: TileType.UNKNOWN
            val h = PmHeader(u64(8), u64(16), u64(24), u64(32), u64(40), u64(48), u64(56), u64(64), internal, tile, type, b[100].toInt() and 0xFF, b[101].toInt() and 0xFF)
            if (internal != Compression.NONE && internal != Compression.GZIP) throw PmTilesException(PmTilesException.Reason.UNSUPPORTED_COMPRESSION)
            if (tile != Compression.NONE && tile != Compression.GZIP) throw PmTilesException(PmTilesException.Reason.UNSUPPORTED_COMPRESSION)
            if (type == TileType.MVT) throw PmTilesException(PmTilesException.Reason.VECTOR_TILES)
            if (type != TileType.PNG && type != TileType.JPEG && type != TileType.WEBP) throw PmTilesException(PmTilesException.Reason.UNSUPPORTED_TILE_TYPE)
            if (h.minZoom > h.maxZoom || h.maxZoom > MAX_ZOOM) throw PmTilesException(PmTilesException.Reason.CORRUPT)
            // every section must lie inside the file, and the directories must be a sane size
            for ((o, l) in listOf(h.rootOffset to h.rootLength, h.metadataOffset to h.metadataLength, h.leafOffset to h.leafLength, h.dataOffset to h.dataLength))
                if (o > fileSize || l > fileSize - o) throw PmTilesException(PmTilesException.Reason.CORRUPT)
            if (h.rootLength <= 0 || h.rootLength > MAX_DIR_BYTES) throw PmTilesException(PmTilesException.Reason.CORRUPT)
            return h
        }

        private fun comp(v: Int) = Compression.entries.firstOrNull { it.code == v } ?: Compression.UNKNOWN
        const val MAX_ZOOM = 26
        const val MAX_DIR_BYTES = 8L shl 20
    }
}

/** One directory entry: [runLength] 0 means a leaf directory at [offset]/[length] in the leaf section; otherwise a tile (repeated for [runLength] ids). */
class DirEntry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

object PmTiles {
    /** Hilbert tile id of z/x/y, as the format defines: every smaller zoom's tiles first, then position along the curve. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..PmHeader.MAX_ZOOM) { "zoom" }
        val n = 1L shl z
        require(x >= 0 && y >= 0 && x < n && y < n) { "tile outside the zoom" }
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        var tx = x.toLong(); var ty = y.toLong(); var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) { tx = n - 1 - tx; ty = n - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return acc + d
    }

    /** Parses one decompressed directory. Throws CORRUPT on a truncated or absurd one. */
    fun parseDirectory(raw: ByteArray): List<DirEntry> {
        var pos = 0
        fun varint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                if (pos >= raw.size || shift > 63) throw PmTilesException(PmTilesException.Reason.CORRUPT)
                val b = raw[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            if (result < 0) throw PmTilesException(PmTilesException.Reason.CORRUPT)
            return result
        }
        val n = varint()
        if (n > raw.size) throw PmTilesException(PmTilesException.Reason.CORRUPT) // at least one byte per entry in each of the four columns
        val count = n.toInt()
        val ids = LongArray(count); val runs = IntArray(count); val lens = IntArray(count); val offs = LongArray(count)
        var last = 0L
        for (i in 0 until count) { last += varint(); ids[i] = last }
        for (i in 0 until count) runs[i] = varint().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        for (i in 0 until count) lens[i] = varint().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        for (i in 0 until count) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
            if (offs[i] < 0) throw PmTilesException(PmTilesException.Reason.CORRUPT)
        }
        return List(count) { DirEntry(ids[it], offs[it], lens[it], runs[it]) }
    }

    /** The entry covering [tileId]: an exact id, or the last entry before it when that one's run reaches it, or a leaf pointer (run 0). Null when absent. */
    fun find(entries: List<DirEntry>, tileId: Long): DirEntry? {
        var lo = 0; var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val id = entries[mid].tileId
            if (id < tileId) lo = mid + 1 else if (id > tileId) hi = mid - 1 else return entries[mid]
        }
        if (hi >= 0) {
            val e = entries[hi]
            if (e.runLength == 0) return e
            if (tileId - e.tileId < e.runLength) return e
        }
        return null
    }

    /** Decompresses [data] (gzip or none), refusing to expand past [limit] bytes (a damaged or hostile archive). */
    fun inflate(data: ByteArray, how: Compression, limit: Int): ByteArray {
        if (how == Compression.NONE) { if (data.size > limit) throw PmTilesException(PmTilesException.Reason.CORRUPT); return data }
        if (how != Compression.GZIP) throw PmTilesException(PmTilesException.Reason.UNSUPPORTED_COMPRESSION)
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
                val out = ByteArrayOutputStream(maxOf(32, data.size * 2))
                val buf = ByteArray(8192)
                while (true) {
                    val r = gz.read(buf)
                    if (r < 0) break
                    if (out.size() + r > limit) throw PmTilesException(PmTilesException.Reason.CORRUPT)
                    out.write(buf, 0, r)
                }
                return out.toByteArray()
            }
        } catch (e: PmTilesException) { throw e } catch (e: IOException) { throw PmTilesException(PmTilesException.Reason.CORRUPT) }
    }
}

/**
 * An open archive. [open] reads the header and the root directory, so a file that is not a usable PMTiles raster archive fails there, before the map uses it.
 * [tile] returns the tile's image bytes (PNG/JPEG/WebP), or null when the archive has no such tile. Thread-safe: reads are positional and the small directory cache is locked.
 */
class PmTilesReader private constructor(private val src: ByteSource, val header: PmHeader, private val root: List<DirEntry>) : Closeable {
    private val leaves = object : LinkedHashMap<Long, List<DirEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<DirEntry>>) = size > MAX_LEAVES
    }

    val minZoom: Int get() = header.minZoom
    val maxZoom: Int get() = header.maxZoom

    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = PmTiles.tileId(z, x, y)
        var dir = root
        for (depth in 0..MAX_DEPTH) {
            val e = PmTiles.find(dir, id) ?: return null
            if (e.runLength > 0) {
                if (e.length <= 0 || e.length > MAX_TILE_BYTES || e.offset + e.length > header.dataLength) throw PmTilesException(PmTilesException.Reason.CORRUPT)
                val raw = src.read(header.dataOffset + e.offset, e.length)
                return PmTiles.inflate(raw, header.tileCompression, MAX_TILE_BYTES)
            }
            if (e.length <= 0 || e.length > PmHeader.MAX_DIR_BYTES || e.offset + e.length > header.leafLength) throw PmTilesException(PmTilesException.Reason.CORRUPT)
            dir = leaf(e)
        }
        throw PmTilesException(PmTilesException.Reason.CORRUPT)
    }

    private fun leaf(e: DirEntry): List<DirEntry> {
        synchronized(leaves) { leaves[e.offset]?.let { return it } }
        val parsed = PmTiles.parseDirectory(PmTiles.inflate(src.read(header.leafOffset + e.offset, e.length), header.internalCompression, DIR_INFLATE_LIMIT))
        synchronized(leaves) { leaves[e.offset] = parsed }
        return parsed
    }

    /** The archive's JSON metadata "attribution" text with any markup removed and cut to 120 characters; null when absent or unreadable. */
    fun attribution(): String? = try {
        if (header.metadataLength <= 0 || header.metadataLength > 1L shl 20) null
        else {
            val json = String(PmTiles.inflate(src.read(header.metadataOffset, header.metadataLength.toInt()), header.internalCompression, 1 shl 20), Charsets.UTF_8)
            val raw = (kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject)?.get("attribution")
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            raw?.replace(Regex("<[^>]*>"), "")?.replace(Regex("\\s+"), " ")?.trim()?.take(120)?.ifBlank { null }
        }
    } catch (e: Exception) { null }

    override fun close() = src.close()

    companion object {
        private const val MAX_DEPTH = 3
        private const val MAX_LEAVES = 32
        const val MAX_TILE_BYTES = 4 shl 20
        private const val DIR_INFLATE_LIMIT = 32 shl 20

        /** Opens [src]; on any failure the source is closed and a [PmTilesException] says why. */
        fun open(src: ByteSource): PmTilesReader {
            try {
                if (src.size < PmHeader.SIZE) throw PmTilesException(PmTilesException.Reason.NOT_PMTILES)
                val header = PmHeader.parse(src.read(0, PmHeader.SIZE), src.size)
                val rootRaw = PmTiles.inflate(src.read(header.rootOffset, header.rootLength.toInt()), header.internalCompression, DIR_INFLATE_LIMIT)
                return PmTilesReader(src, header, PmTiles.parseDirectory(rootRaw))
            } catch (e: PmTilesException) { runCatching { src.close() }; throw e }
            catch (e: IOException) { runCatching { src.close() }; throw PmTilesException(PmTilesException.Reason.UNREADABLE) }
            catch (e: RuntimeException) { runCatching { src.close() }; throw PmTilesException(PmTilesException.Reason.CORRUPT) }
        }
    }
}
