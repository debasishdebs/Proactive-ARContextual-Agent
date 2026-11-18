package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** Builds tiny PMTiles v3 archives in memory, independently of the reader's directory parser (the writer side of the spec). */
object PmTilesFixture {
    class Tile(val z: Int, val x: Int, val y: Int, val bytes: ByteArray, val run: Int = 1)

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x >= 0x80) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }
        out.write(x.toInt())
    }

    /** One directory of (tileId, offset, length, run) rows, already sorted by id. */
    fun directory(rows: List<LongArray>): ByteArray {
        val o = ByteArrayOutputStream()
        varint(o, rows.size.toLong())
        var last = 0L
        for (r in rows) { varint(o, r[0] - last); last = r[0] }
        for (r in rows) varint(o, r[3])
        for (r in rows) varint(o, r[2])
        var prevEnd = -1L
        for ((i, r) in rows.withIndex()) {
            if (i > 0 && r[1] == prevEnd) varint(o, 0) else varint(o, r[1] + 1)
            prevEnd = r[1] + r[2]
        }
        return o.toByteArray()
    }

    /**
     * [tiles] are PNG-ish byte blobs. [leaf] puts every tile into one leaf directory behind a single root pointer (exercises the two-level lookup).
     * [dirGzip] and [tileGzip] choose the compression. [type] is the header tile type (2 = PNG).
     */
    fun build(
        tiles: List<Tile>, leaf: Boolean = false, dirGzip: Boolean = true, tileGzip: Boolean = false, type: Int = 2, version: Int = 3,
        metadata: String = "{}", internalCode: Int? = null, tileCode: Int? = null, badLength: Long? = null,
    ): ByteArray {
        val sorted = tiles.sortedBy { PmTiles.tileId(it.z, it.x, it.y) }
        val data = ByteArrayOutputStream()
        val rows = ArrayList<LongArray>()
        for (t in sorted) {
            val payload = if (tileGzip) gzip(t.bytes) else t.bytes
            rows += longArrayOf(PmTiles.tileId(t.z, t.x, t.y), data.size().toLong(), payload.size.toLong(), t.run.toLong())
            data.write(payload)
        }
        if (badLength != null) rows[0][2] = badLength
        fun pack(d: ByteArray) = if (dirGzip) gzip(d) else d
        val leafBytes: ByteArray
        val root: ByteArray
        if (leaf) {
            leafBytes = pack(directory(rows))
            root = pack(directory(listOf(longArrayOf(rows.first()[0], 0, leafBytes.size.toLong(), 0))))
        } else { leafBytes = ByteArray(0); root = pack(directory(rows)) }
        val meta = pack(metadata.toByteArray())
        val rootOff = 127L; val metaOff = rootOff + root.size; val leafOff = metaOff + meta.size; val dataOff = leafOff + leafBytes.size
        val h = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()); h.put(version.toByte())
        for (v in longArrayOf(rootOff, root.size.toLong(), metaOff, meta.size.toLong(), leafOff, leafBytes.size.toLong(), dataOff, data.size().toLong(), sorted.size.toLong(), rows.size.toLong(), sorted.size.toLong())) h.putLong(v)
        h.put(1) // clustered
        h.put((internalCode ?: if (dirGzip) 2 else 1).toByte()); h.put((tileCode ?: if (tileGzip) 2 else 1).toByte()); h.put(type.toByte())
        h.put(sorted.minOf { it.z }.toByte()); h.put(sorted.maxOf { it.z }.toByte())
        h.putInt(0); h.putInt(0); h.putInt(0); h.putInt(0); h.put(0); h.putInt(0); h.putInt(0)
        val out = ByteArrayOutputStream()
        out.write(h.array()); out.write(root); out.write(meta); out.write(leafBytes); out.write(data.toByteArray())
        return out.toByteArray()
    }

    fun png(n: Int) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), n.toByte())

    /** A small archive: z0, z1 (four tiles) and a few z2 tiles. */
    fun sample(vararg opts: Boolean) = listOf(Tile(0, 0, 0, png(0))) +
        listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1).map { (x, y) -> Tile(1, x, y, png(10 + x * 2 + y)) } +
        listOf(Tile(2, 3, 1, png(31)), Tile(2, 0, 0, png(20)))
}
