package com.rokid.context.glasses.capture

import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.Wire
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.TreeMap

@Serializable
data class BufferedMeta(val frameId: String, val capturedAtMs: Long, val luma: Int? = null, val phash: String? = null, val batteryPct: Int? = null)

data class BufferedFrame(val meta: BufferedMeta, val jpeg: File)

/** Write-to-temp, fsync, rename: a reader sees the whole file or none of it. */
fun atomicWrite(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, "${target.name}.tmp")
    try {
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
}

/**
 * Frames waiting for the phone (spec §1.7, §3.1): up to [capacity] (200 ≈ 4 MB) in app storage so a restart keeps
 * them; FIFO, the oldest dropped first when full; frames older than [maxAgeMs] (72 h, spec §5.5) are dropped. Each
 * entry is a JPEG plus a meta file, both written atomically; an entry missing either one is a crash leftover and is
 * discarded on load. A frame leaves only through [remove] (positive ack or second NACK) or [clear] (privacy pause).
 */
class OutboundBuffer(
    private val dir: File,
    private val capacity: Int = 200,
    private val maxAgeMs: Long = 72 * 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
    private val log: (String) -> Unit = {},
) {
    /** Key "<capturedAtMs, 15 digits>_<frameId>" sorts in capture order. */
    private val frames = TreeMap<String, BufferedFrame>()

    init { load() }

    val size: Int @Synchronized get() = frames.size

    /** False when the frame could not be stored (e.g. storage full); nothing is left behind then. */
    @Synchronized fun add(frameId: String, jpeg: ByteArray, capturedAtMs: Long, luma: Int?, phash: String?, batteryPct: Int?): Boolean {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        val key = keyOf(capturedAtMs, frameId)
        val j = File(dir, "$key.jpg")
        val m = File(dir, "$key.json")
        val meta = BufferedMeta(frameId, capturedAtMs, luma, phash, batteryPct)
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create buffer directory")
            writer(j, jpeg)
            writer(m, Wire.json.encodeToString(BufferedMeta.serializer(), meta).toByteArray())
        } catch (e: IOException) {
            j.delete(); m.delete()
            log("frame $frameId not buffered: ${e.javaClass.simpleName}")
            return false
        }
        frames[key] = BufferedFrame(meta, j)
        while (frames.size > capacity) { log("buffer full: dropped ${frames.firstEntry().value.meta.frameId}"); delete(frames.firstKey()) }
        return true
    }

    /** The oldest frame young enough to send; older ones are dropped on the way. */
    @Synchronized fun oldest(): BufferedFrame? {
        val cutoff = clock() - maxAgeMs
        while (frames.isNotEmpty()) {
            val (key, f) = frames.firstEntry()
            if (f.meta.capturedAtMs >= cutoff) return f
            log("dropped ${f.meta.frameId}: older than 72 h")
            delete(key)
        }
        return null
    }

    /** False once the frame has left the buffer (acked, dropped, aged out or cleared). */
    @Synchronized fun contains(frameId: String): Boolean = frames.values.any { it.meta.frameId == frameId }

    @Synchronized fun remove(frameId: String) {
        frames.entries.firstOrNull { it.value.meta.frameId == frameId }?.key?.let { delete(it) }
    }

    @Synchronized fun clear() { frames.keys.toList().forEach { delete(it) } }

    fun readJpeg(f: BufferedFrame): ByteArray = f.jpeg.readBytes()

    private fun delete(key: String) {
        frames.remove(key)
        File(dir, "$key.jpg").delete()
        File(dir, "$key.json").delete()
    }

    private fun load() {
        val files = dir.listFiles().orEmpty()
        files.filter { it.extension == "tmp" }.forEach { it.delete() }
        val jpgs = files.filter { it.extension == "jpg" }.associateBy { it.nameWithoutExtension }
        val metas = files.filter { it.extension == "json" }.associateBy { it.nameWithoutExtension }
        for (key in jpgs.keys + metas.keys) {
            val j = jpgs[key]
            val m = metas[key]
            val meta = if (j != null && m != null) runCatching { Wire.json.decodeFromString(BufferedMeta.serializer(), m.readText()) }.getOrNull() else null
            if (j == null || meta == null || !FrameCodec.isValidFrameId(meta.frameId)) { j?.delete(); m?.delete(); continue }
            frames[key] = BufferedFrame(meta, j)
        }
        while (frames.size > capacity) delete(frames.firstKey())
    }

    private fun keyOf(capturedAtMs: Long, frameId: String) = String.format(Locale.ROOT, "%015d_%s", capturedAtMs, frameId)
}
