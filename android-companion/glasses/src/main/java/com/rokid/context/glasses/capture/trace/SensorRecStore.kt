package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.ChunkCodec
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFiles
import com.rokid.context.capture.trace.TraceFormatException
import com.rokid.context.core.model.TraceChunk
import java.io.File

/**
 * The sensor-recording batches on the glasses (v0.9.7 B): `files/imu/sensor_rec/s-<startWallMs>-<endWallMs>-<seq>.json`, each ONE `TraceChunk` of kind `sensor` in the frozen chunk codec
 * (gzip, base64, sha256), so a pull and a decoder read it like a trace chunk. Deliberately NOT under `traces/` and with no manifest: trace import and replay never see it, and it never feeds
 * a classifier. The file name carries the wall-clock interval, so a range Forget works without reading the file. Size-bounded: [capBytes] in total, the oldest batch is pruned first; nothing is
 * written under [floorBytes] of free space. Called from the trace writer thread only (the same single thread as the epoch wipe and the forget run, so they are ordered).
 */
internal class SensorRecStore(
    val dir: File,
    private val capBytes: Long = 16L shl 20,
    private val freeBytes: () -> Long,
    private val floorBytes: Long = 64L shl 20,
    private val deleteTree: (File) -> Boolean = { it.deleteRecursively() },
) {
    enum class Result { WRITTEN, NO_ROOM, REFUSED, IO }

    /** Batches deleted by the last [write] to make room. */
    @Volatile var lastPruned: Int = 0
        private set

    private class Named(val file: File, val start: Long, val end: Long, val seq: Int)

    private fun named(): List<Named> = (dir.listFiles() ?: emptyArray()).mapNotNull { f ->
        NAME.matchEntire(f.name)?.let { m -> Named(f, m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toInt()) }
    }.sortedWith(compareBy({ it.start }, { it.seq }))

    fun totalBytes(): Long = (dir.listFiles() ?: emptyArray()).sumOf { it.length() }

    fun write(startWallMs: Long, endWallMs: Long, seq: Int, events: List<TraceEvent>): Result {
        lastPruned = 0
        val s = seq.mod(MAX_SEQ)
        val text = try {
            val id = "sensor-rec-$startWallMs"
            val e = ChunkCodec.encode(id, s, events)
            TraceFiles.encodeFile(TraceChunk(traceId = id, kind = KIND, seq = s, count = 0, sha256 = e.sha256, b64 = e.b64, atMs = endWallMs))
        } catch (x: TraceFormatException) { return Result.REFUSED }
        val size = text.length.toLong()
        if (size > capBytes) return Result.REFUSED
        if (freeBytes() < floorBytes + size) return Result.NO_ROOM
        var total = totalBytes()
        for (n in named()) {
            if (total + size <= capBytes) break
            val len = n.file.length()
            if (n.file.delete()) { total -= len; lastPruned++ } else return Result.IO
        }
        // anything that is not one of our files counts against the cap too and is removed first (nothing else lives here)
        (dir.listFiles() ?: emptyArray()).filter { NAME.matchEntire(it.name) == null }.forEach { it.delete() }
        if (total + size > capBytes) return Result.IO
        dir.mkdirs()
        val name = "s-$startWallMs-$endWallMs-${"%04d".format(s)}.json"
        val part = File(dir, "$name.part")
        return try {
            part.writeText(text, Charsets.UTF_8)
            if (part.renameTo(File(dir, name))) Result.WRITTEN else { part.delete(); Result.IO }
        } catch (e: java.io.IOException) { part.delete(); Result.IO }
    }

    /** Epoch wipe (Forget all / Delete all): every batch goes. True only when the folder is really gone. */
    fun wipe(): Boolean { deleteTree(dir); return !dir.exists() }

    /** Range Forget (glasses wall clock): every batch whose interval overlaps a range, and every file whose name cannot be read (it cannot be shown not to overlap), is deleted. True when all of them are gone. */
    fun forget(ranges: List<LongRange>): Boolean {
        if (ranges.isEmpty()) return true
        var ok = true
        for (f in dir.listFiles() ?: emptyArray()) {
            val m = NAME.matchEntire(f.name)
            val hit = m == null || ranges.any { m.groupValues[1].toLong() <= it.last && m.groupValues[2].toLong() >= it.first }
            if (hit) { deleteTree(f); if (f.exists()) ok = false }
        }
        return ok
    }

    companion object {
        const val KIND = "sensor"
        private const val MAX_SEQ = 4096
        private val NAME = Regex("^s-([0-9]{1,16})-([0-9]{1,16})-([0-9]{4})\\.json$")
    }
}
