package com.rokid.context.data.frames

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The glasses-side keyframe metadata (T0 fields) that travels with a v2 receipt in its `<base>.meta.json` sidecar, plus the
 * JPEG's on-disk [size]. A resend is matched against it field for field.
 */
data class ReceiptMeta(
    val wireFrameId: String,
    val capturedAtMs: Long,
    val motion: String = "UNKNOWN",
    val dwell: Boolean = false,
    val blur: Float? = null,
    val dhash: String? = null,
    val phash: String? = null,
    val luma: Int? = null,
    val batteryPct: Int? = null,
    val width: Int? = null,
    val sampleSeq: Long? = null,
    val sessionId: String? = null,
    val size: Long = 0,
    /** v0.9.6 slice C: `"<epoch>:<seq>"` of the mark this photo was taken for (null = an ordinary frame). */
    val markRef: String? = null,
) {
    fun toJson(): ByteArray = JSONObject().apply {
        put("v", 1)
        put("wireFrameId", wireFrameId); put("capturedAtMs", capturedAtMs); put("motion", motion); put("dwell", dwell)
        blur?.let { put("blur", it.toDouble()) }; dhash?.let { put("dhash", it) }; phash?.let { put("phash", it) }
        luma?.let { put("luma", it) }; batteryPct?.let { put("batteryPct", it) }; width?.let { put("width", it) }
        sampleSeq?.let { put("sampleSeq", it) }; sessionId?.let { put("sessionId", it) }; markRef?.let { put("markRef", it) }; put("size", size)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        /** Null when [file] is missing, unreadable or not a sidecar this version wrote. */
        fun read(file: File): ReceiptMeta? = try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            ReceiptMeta(
                wireFrameId = o.getString("wireFrameId"), capturedAtMs = o.getLong("capturedAtMs"),
                motion = o.optString("motion", "UNKNOWN"), dwell = o.optBoolean("dwell", false),
                blur = if (o.has("blur")) o.getDouble("blur").toFloat() else null,
                dhash = o.optString("dhash").ifEmpty { null }, phash = o.optString("phash").ifEmpty { null },
                luma = if (o.has("luma")) o.getInt("luma") else null,
                batteryPct = if (o.has("batteryPct")) o.getInt("batteryPct") else null,
                width = if (o.has("width")) o.getInt("width") else null,
                sampleSeq = if (o.has("sampleSeq")) o.getLong("sampleSeq") else null,
                sessionId = o.optString("sessionId").ifEmpty { null }, size = o.optLong("size", 0), markRef = o.optString("markRef").ifEmpty { null },
            )
        } catch (e: IOException) { null } catch (e: JSONException) { null }
    }
}

/** v2 receipt names: base `rokid_<rxMs>_<id>.v2`, with `<base>.jpg` and `<base>.meta.json` (the archive prefixes `<archiveMs>_`). */
object ReceiptNames {
    const val JPG = ".jpg"
    const val SIDECAR = ".meta.json"
    private val V2 = Regex("^(?:\\d+_)?(rokid_(\\d+)_(.+)\\.v2)(\\.jpg|\\.meta\\.json)$")

    fun stem(rxMs: Long, wireId: String) = "rokid_${rxMs}_$wireId.v2"

    /** Same location and archive prefix as [jpeg], other half of the pair. */
    fun sidecarBeside(jpeg: File) = File(jpeg.parentFile, jpeg.name.removeSuffix(JPG) + SIDECAR)
    fun jpegBeside(sidecar: File) = File(sidecar.parentFile, sidecar.name.removeSuffix(SIDECAR) + JPG)

    fun isV2Jpeg(file: File) = file.name.endsWith(".v2$JPG")

    class Parsed(val stem: String, val rxMs: Long, val wireId: String, val isSidecar: Boolean)

    fun parse(name: String): Parsed? = V2.matchEntire(name)?.let {
        Parsed(it.groupValues[1], it.groupValues[2].toLongOrNull() ?: return null, it.groupValues[3], it.groupValues[4] == SIDECAR)
    }
}

/** What one v2 receipt looks like on disk right now: its files may sit in the inbox, the archive, or be split between them. */
class Receipt(val stem: String, val rxMs: Long, val wireId: String, val jpeg: File?, val sidecar: File?) {
    val dirs: Set<File> get() = listOfNotNull(jpeg?.parentFile, sidecar?.parentFile).toSet()
}

object ReceiptScan {
    /** Every v2 receipt in [dirs] (archive entries win over inbox ones), keyed by stem. `.part` temps are not receipts. */
    fun scan(vararg dirs: File): Map<String, Receipt> {
        val jpegs = HashMap<String, File>(); val sidecars = HashMap<String, File>(); val info = HashMap<String, ReceiptNames.Parsed>()
        for (dir in dirs) for (f in dir.listFiles().orEmpty()) {
            if (!f.isFile) continue
            val p = ReceiptNames.parse(f.name) ?: continue
            info[p.stem] = p
            (if (p.isSidecar) sidecars else jpegs)[p.stem] = f
        }
        return info.mapValues { (stem, p) -> Receipt(stem, p.rxMs, p.wireId, jpegs[stem], sidecars[stem]) }
    }

    fun find(wireId: String, vararg dirs: File): Receipt? = scan(*dirs).values.firstOrNull { it.wireId == wireId }
}

/** temp, fsync, rename: the file is whole or absent. The caller syncs the directory. */
internal fun writeDurably(target: File, bytes: ByteArray, lastModifiedMs: Long? = null) {
    val tmp = File(target.parentFile, "${target.name}.part")
    try {
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        lastModifiedMs?.let { tmp.setLastModified(it) }
        if (!tmp.renameTo(target)) throw IOException("rename failed")
    } catch (e: IOException) {
        tmp.delete()
        throw e
    }
}
