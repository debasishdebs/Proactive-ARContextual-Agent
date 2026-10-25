package com.rokid.context.glasses.capture

import android.os.SystemClock
import com.rokid.context.core.model.FileDurability
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.Wire
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.TreeMap

@Serializable
data class BufferedMeta(val frameId: String, val capturedAtMs: Long, val luma: Int? = null, val phash: String? = null, val batteryPct: Int? = null, val elapsedAtCaptureMs: Long? = null, val bootId: String? = null,
    // Keyframe metadata (additive: a frame buffered before the sampling session has none and sends with defaults).
    val motion: MotionState = MotionState.UNKNOWN, val dwell: Boolean = false, val blur: Float? = null, val dhash: String? = null,
    val width: Int? = null, val sampleSeq: Long? = null, val sessionId: String? = null,
    /** v0.9.6 slice C, additive: `"<epoch>:<seq>"` of the mark this photo was taken for (null = an ordinary frame). */
    val markRef: String? = null,
)

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
 * Frames waiting for the phone (spec §1.7, §3.1): up to [capacity] (200 ≈ 4 MB) and [maxBytes] of JPEG in app storage so a restart keeps
 * them; FIFO, the oldest dropped first when full (a non-dwell keyframe before any dwell one); frames older than [maxAgeMs] (72 h, spec §5.5) are dropped. Each
 * entry is a JPEG plus a meta file, both written atomically; an entry missing either one is a crash leftover and is
 * discarded on load. A frame leaves only through [remove] (positive ack or second NACK) or [clear] (privacy pause).
 */
class OutboundBuffer(
    private val dir: File,
    private val capacity: Int = 200,
    private val maxBytes: Long = 8L * 1024 * 1024,
    private val maxAgeMs: Long = 72 * 3_600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val bootId: () -> String = ::currentBootId,
    private val writer: (File, ByteArray) -> Unit = ::atomicWrite,
    private val fsyncDir: (File) -> Boolean = FileDurability::fsyncDir,
    private val log: (String) -> Unit = {},
) {
    /** Key "<capturedAtMs, 15 digits>_<frameId>" sorts in capture order. */
    private val frames = TreeMap<String, BufferedFrame>()
    private val sizes = HashMap<String, Long>()
    private var totalBytes = 0L
    private var fsyncWarned = false
    private val boot: String by lazy { bootId() }

    init { load() }

    val size: Int @Synchronized get() = frames.size

    /**
     * False when the frame could not be stored; nothing is left behind then. When storage is full and the buffer is
     * not empty, the oldest frame makes room (FIFO, like the count cap) and the write is retried once.
     */
    @Synchronized fun add(frameId: String, jpeg: ByteArray, capturedAtMs: Long, luma: Int?, phash: String?, batteryPct: Int?, elapsedAtCaptureMs: Long = elapsed(), keyframe: KeyframeOut? = null, markRef: String? = null): Boolean {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        val key = keyOf(capturedAtMs, frameId)
        val j = File(dir, "$key.jpg")
        val m = File(dir, "$key.json")
        val meta = BufferedMeta(frameId, capturedAtMs, luma, phash, batteryPct, elapsedAtCaptureMs, boot.ifEmpty { null },
            keyframe?.motion ?: MotionState.UNKNOWN, keyframe?.dwell ?: false, keyframe?.blur, keyframe?.dhash, keyframe?.width, keyframe?.sampleSeq, keyframe?.sessionId, markRef)
        val metaBytes = Wire.json.encodeToString(BufferedMeta.serializer(), meta).toByteArray()
        if (!write(j, m, jpeg, metaBytes)) {
            if (frames.isEmpty()) return false
            val victim = dropVictim()
            log("storage full: dropped 1 frame buffered=${frames.size}")
            delete(victim)
            if (!write(j, m, jpeg, metaBytes)) return false
        }
        if (!fsyncDir(dir) && !fsyncWarned) { fsyncWarned = true; log("buffer directory not fsynced") }
        frames[key] = BufferedFrame(meta, j)
        sizes.put(key, jpeg.size.toLong())?.let { totalBytes -= it } // a re-add replaces the old entry
        totalBytes += jpeg.size
        enforceCaps()
        return true
    }

    /** The oldest frame young enough to send; older ones (and ones of unknowable age) are dropped on the way. */
    @Synchronized fun oldest(): BufferedFrame? {
        while (frames.isNotEmpty()) {
            val (key, f) = frames.firstEntry()
            val age = ageMs(f.meta)
            if (age != null && age <= maxAgeMs) return f
            log("dropped 1 frame: ${if (age == null) "unknown age" else "older than 72 h"}")
            delete(key)
        }
        return null
    }

    /** False once the frame has left the buffer (acked, dropped, aged out or cleared). */
    @Synchronized fun contains(frameId: String): Boolean = frames.values.any { it.meta.frameId == frameId }

    @Synchronized fun remove(frameId: String) {
        frames.entries.firstOrNull { it.value.meta.frameId == frameId }?.key?.let { delete(it) }
    }

    /** The user undid the mark: its photo, if it has not left the buffer yet, goes. False when none is here (delivered, or never taken); the phone fences and forgets one that is already on its way. */
    @Synchronized fun discardMarkPhoto(markRef: String): Boolean {
        val keys = frames.entries.filter { it.value.meta.markRef == markRef }.map { it.key }
        keys.forEach { delete(it) }
        return keys.isNotEmpty()
    }

    @Synchronized fun clear() { frames.keys.toList().forEach { delete(it) } }

    /** JPEG bytes held; capped at [maxBytes] alongside the [capacity] frame count. */
    val bytes: Long @Synchronized get() = totalBytes

    /**
     * The capture instant on today's wall clock: for a frame of this boot, now minus its uptime age, so a clock change
     * (NTP, the user) between capture and send cancels out. Otherwise the stored capture time.
     */
    @Synchronized fun sendTimeMs(f: BufferedFrame): Long {
        val e = f.meta.elapsedAtCaptureMs
        return if (e != null && sameBoot(f.meta)) clock() - (elapsed() - e) else f.meta.capturedAtMs
    }

    fun readJpeg(f: BufferedFrame): ByteArray = f.jpeg.readBytes()

    private fun sameBoot(m: BufferedMeta) = m.elapsedAtCaptureMs != null && !m.bootId.isNullOrEmpty() && m.bootId == boot

    /**
     * Uptime age within one boot (immune to clock jumps); otherwise wall-clock age. Null when it cannot be known: a frame
     * from another boot dated over an hour in the future of the wall clock (fail closed, spec 5.5).
     */
    private fun ageMs(m: BufferedMeta): Long? {
        val e = m.elapsedAtCaptureMs
        if (e != null && sameBoot(m)) return elapsed() - e
        val now = clock()
        return if (m.capturedAtMs > now + FUTURE_SLACK_MS) null else now - m.capturedAtMs
    }

    private fun write(j: File, m: File, jpeg: ByteArray, meta: ByteArray): Boolean = try {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create buffer directory")
        writer(j, jpeg)
        writer(m, meta)
        true
    } catch (e: IOException) {
        j.delete(); m.delete()
        log("frame not buffered: ${e.javaClass.simpleName}")
        false
    }

    /** Count and byte caps, oldest first; the newest frame always stays. */
    private fun enforceCaps(logDrops: Boolean = true) {
        while (frames.size > 1 && (frames.size > capacity || totalBytes > maxBytes)) {
            val victim = dropVictim()
            if (logDrops) log("buffer full: dropped 1 frame buffered=${frames.size}")
            delete(victim)
        }
    }

    /** The oldest non-dwell frame (never the newest); when only dwell frames are left, the oldest of those. */
    private fun dropVictim(): String {
        val newest = frames.lastKey()
        return frames.entries.firstOrNull { it.key != newest && !it.value.meta.dwell }?.key ?: frames.firstKey()
    }

    private fun delete(key: String) {
        frames.remove(key)
        sizes.remove(key)?.let { totalBytes -= it }
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
            val len = j.length()
            sizes[key] = len
            totalBytes += len
        }
        enforceCaps(logDrops = false)
    }

    private fun keyOf(capturedAtMs: Long, frameId: String) = String.format(Locale.ROOT, "%015d_%s", capturedAtMs, frameId)

    private companion object { const val FUTURE_SLACK_MS = 3_600_000L }
}

/** This boot's id from the kernel; "" when unreadable, which makes every frame's age wall-clock based. */
internal fun currentBootId(): String = try {
    File("/proc/sys/kernel/random/boot_id").readText().trim()
} catch (e: IOException) {
    ""
} catch (e: SecurityException) {
    ""
}
