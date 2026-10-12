package com.rokid.context.data.frames

import android.content.Context
import android.util.Log
import java.io.File

data class CapturedFrame(
    val frameId: String,
    val capturedAtEpochMs: Long,
    val source: String,
    val payload: String,
    val previewImagePath: String,
)

object FrameStreamStore {
    private const val TAG = "FrameStreamStore"

    private fun <T> io(context: Context, block: suspend (com.rokid.context.memory.MemoryGraph) -> T): T {
        com.rokid.context.memory.MemoryGraph.ensureLegacyImported(context)
        val graph = com.rokid.context.memory.MemoryGraph.get(context)
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { block(graph) }
    }

    private fun com.rokid.context.memory.db.FrameEntity.toCaptured() = CapturedFrame(id, ts, source, payload, path)

    fun append(context: Context, frame: CapturedFrame): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) { Log.w(TAG, "append rejected non-rokid source=${frame.source}"); return false }
        val preview = ensurePreviewPath(frame)
        if (preview.isBlank()) { Log.w(TAG, "append rejected preview missing frameId=${frame.frameId} path=${frame.previewImagePath}"); return false }
        val safePayload = frame.payload.replace("\n", " ").replace("\t", " ").take(220)
        val ok = io(context) { it.frames.add(com.rokid.context.memory.db.FrameEntity(id = frame.frameId, ts = frame.capturedAtEpochMs, path = preview, source = frame.source, payload = safePayload)) }
        Log.i(TAG, "append ok=$ok frameId=${frame.frameId}")
        return ok
    }

    private fun existing(list: List<com.rokid.context.memory.db.FrameEntity>) =
        list.filter { it.source.startsWith("rokid", true) && it.path.isNotBlank() && File(it.path).exists() }.map { it.toCaptured() }

    fun recent(context: Context, limit: Int): List<CapturedFrame> = io(context) { existing(it.frames.recent(limit.coerceAtLeast(1) * 2)) }.take(limit.coerceAtLeast(1))
    fun all(context: Context): List<CapturedFrame> = io(context) { existing(it.frames.all()) }
    fun byIds(context: Context, frameIds: List<String>): List<CapturedFrame> {
        val wanted = frameIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return if (wanted.isEmpty()) emptyList() else io(context) { existing(it.frames.byIds(wanted)) }
    }

    fun purgeNonRokidEntries(context: Context) {
        io(context) { g ->
            g.frames.deleteByIds(g.frames.all().filter { !it.source.startsWith("rokid", true) || it.path.isBlank() }.map { it.id })
        }
    }

    data class PurgeResult(
        val kept: Int,
        val dropped: Int,
        val deletedFiles: Int,
    )

    fun purgeNonPhotoAndSynthetic(context: Context): PurgeResult = io(context) { g ->
        val all = g.frames.all().map { it.toCaptured() }
        val (kept, dropped) = all.partition(::isRealPhotoFrame)
        g.frames.deleteByIds(dropped.map { it.frameId })
        val deleted = dropped.map { it.previewImagePath }.filter { it.isNotBlank() }.distinct().count { File(it).let { f -> f.exists() && f.delete() } }
        PurgeResult(kept = kept.size, dropped = dropped.size, deletedFiles = deleted)
    }

    fun isRealPhotoFrame(frame: CapturedFrame): Boolean {
        if (!frame.source.startsWith("rokid", ignoreCase = true)) return false
        if (frame.previewImagePath.isBlank()) return false
        val payload = frame.payload.lowercase()
        val path = frame.previewImagePath.lowercase()
        if (payload.contains("synthetic") || path.contains("synthetic")) return false
        if (payload.contains("screenshot") || path.contains("screenshot")) return false
        val ext = File(frame.previewImagePath).extension.lowercase()
        return ext in setOf("jpg", "jpeg", "webp")
    }

    private fun ensurePreviewPath(frame: CapturedFrame): String {
        if (frame.previewImagePath.isBlank()) return ""
        val source = File(frame.previewImagePath)
        return if (source.exists()) source.absolutePath else ""
    }
}
