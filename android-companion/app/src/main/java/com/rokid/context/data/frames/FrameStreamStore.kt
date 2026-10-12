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
    private const val FILE_NAME = "captured_frames.log"
    private const val TAG = "FrameStreamStore"
    private val lock = Any()

    fun append(context: Context, frame: CapturedFrame): Boolean {
        synchronized(lock) {
            if (!frame.source.startsWith("rokid", ignoreCase = true)) {
                Log.w(TAG, "append rejected non-rokid source=${frame.source}")
                return false
            }
            val file = file(context)
            if (!file.exists()) file.createNewFile()
            val safePayload = frame.payload.replace("\n", " ").replace("\t", " ").take(220)
            val preview = ensurePreviewPath(frame)
            if (preview.isBlank()) {
                Log.w(TAG, "append rejected preview missing frameId=${frame.frameId} path=${frame.previewImagePath}")
                return false
            }
            file.appendText("${frame.frameId}\t${frame.capturedAtEpochMs}\t${frame.source}\t$safePayload\t$preview\n")
            Log.i(TAG, "append success frameId=${frame.frameId} preview=$preview")
            return true
        }
    }

    fun recent(context: Context, limit: Int): List<CapturedFrame> {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return emptyList()
            return file.readLines()
                .asReversed()
                .asSequence()
                .mapNotNull { parse(it) }
                .filter { it.source.startsWith("rokid", ignoreCase = true) }
                .map { parsed ->
                    if (parsed.previewImagePath.isBlank()) return@map null
                    val preview = File(parsed.previewImagePath)
                    if (preview.exists()) parsed else null
                }
                .filterNotNull()
                .take(limit.coerceAtLeast(1))
                .toList()
        }
    }

    fun all(context: Context): List<CapturedFrame> {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return emptyList()
            return file.readLines()
                .asSequence()
                .mapNotNull { parse(it) }
                .filter { it.source.startsWith("rokid", ignoreCase = true) }
                .map { parsed ->
                    if (parsed.previewImagePath.isBlank()) return@map null
                    val preview = File(parsed.previewImagePath)
                    if (preview.exists()) parsed else null
                }
                .filterNotNull()
                .toList()
        }
    }

    fun byIds(context: Context, frameIds: List<String>): List<CapturedFrame> {
        if (frameIds.isEmpty()) return emptyList()
        val wanted = frameIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return all(context).filter { it.frameId in wanted }
    }

    fun purgeNonRokidEntries(context: Context) {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return
            val filtered = file.readLines().mapNotNull { line ->
                val parsed = parse(line) ?: return@mapNotNull null
                if (!parsed.source.startsWith("rokid", ignoreCase = true)) return@mapNotNull null
                if (parsed.previewImagePath.isBlank()) return@mapNotNull null
                line
            }
            file.writeText(filtered.joinToString(separator = "\n"))
            if (filtered.isNotEmpty()) file.appendText("\n")
        }
    }

    data class PurgeResult(
        val kept: Int,
        val dropped: Int,
        val deletedFiles: Int,
    )

    fun purgeNonPhotoAndSynthetic(context: Context): PurgeResult {
        synchronized(lock) {
            val file = file(context)
            if (!file.exists()) return PurgeResult(kept = 0, dropped = 0, deletedFiles = 0)

            val parsed = file.readLines().mapNotNull(::parse)
            val kept = mutableListOf<CapturedFrame>()
            val toDelete = mutableListOf<File>()

            parsed.forEach { frame ->
                if (isRealPhotoFrame(frame)) {
                    kept += frame
                } else {
                    if (frame.previewImagePath.isNotBlank()) {
                        val preview = File(frame.previewImagePath)
                        if (preview.exists()) {
                            toDelete += preview
                        }
                    }
                }
            }

            val rewritten = kept.joinToString(separator = "\n") {
                val safePayload = it.payload.replace("\n", " ").replace("\t", " ").take(220)
                "${it.frameId}\t${it.capturedAtEpochMs}\t${it.source}\t$safePayload\t${it.previewImagePath}"
            }
            file.writeText(
                if (rewritten.isBlank()) {
                    ""
                } else {
                    "$rewritten\n"
                },
            )

            var deleted = 0
            toDelete.distinctBy { it.absolutePath }.forEach { candidate ->
                if (candidate.exists() && candidate.delete()) deleted += 1
            }

            return PurgeResult(
                kept = kept.size,
                dropped = (parsed.size - kept.size).coerceAtLeast(0),
                deletedFiles = deleted,
            )
        }
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

    private fun parse(line: String): CapturedFrame? {
        val parts = line.split("\t")
        if (parts.size < 5) return null
        return CapturedFrame(
            frameId = parts[0],
            capturedAtEpochMs = parts[1].toLongOrNull() ?: return null,
            source = parts[2],
            payload = parts[3],
            previewImagePath = parts[4],
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun ensurePreviewPath(frame: CapturedFrame): String {
        if (frame.previewImagePath.isBlank()) return ""
        val source = File(frame.previewImagePath)
        return if (source.exists()) source.absolutePath else ""
    }
}
