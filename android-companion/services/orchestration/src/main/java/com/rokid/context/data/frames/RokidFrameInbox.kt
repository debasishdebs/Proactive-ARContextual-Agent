package com.rokid.context.data.frames

import android.content.Context
import android.util.Log
import com.rokid.context.data.runtime.PipelineStatsStore
import java.io.File
import java.util.Locale

object RokidFrameInbox {
    private const val TAG = "RokidFrameInbox"
    private val supportedExt = setOf("jpg", "jpeg", "png", "webp")

    fun inboxDir(context: Context): File {
        return File(context.filesDir, "rokid_inbox")
    }

    fun archiveDir(context: Context): File {
        return File(context.filesDir, "rokid_archive")
    }

    fun ingestPending(context: Context, maxItems: Int = 8): Int {
        val inbox = inboxDir(context)
        if (!inbox.exists()) inbox.mkdirs()
        val archive = archiveDir(context)
        if (!archive.exists()) archive.mkdirs()

        val candidates = inbox.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { extOf(it.name) in supportedExt }
            ?.sortedBy { it.lastModified() }
            ?.take(maxItems.coerceAtLeast(1))
            ?.toList()
            .orEmpty()
        Log.i(TAG, "ingestPending inbox=${inbox.absolutePath} files=${candidates.size}")

        var imported = 0
        for (file in candidates) {
            val archived = File(archive, "${System.currentTimeMillis()}_${file.name}")
            val moved = runCatching { file.renameTo(archived) }.getOrDefault(false)
            val sourceFile = if (moved) archived else file
            val ok = ingestFile(context, sourceFile, source = "rokid_glasses")
            if (ok) imported += 1
        }
        return imported
    }

    fun ingestSinglePath(context: Context, absolutePath: String, source: String = "rokid_glasses"): Boolean {
        val file = File(absolutePath)
        if (!file.exists() || !file.isFile) return false
        if (extOf(file.name) !in supportedExt) return false
        return ingestFile(context, file, source)
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    // Full archived file name (<archiveMs>_rokid_<rxMs>_<id>): unique per file. Never truncate, ids only dedupe via insertIgnore.
    private fun frameIdOf(file: File): String = file.nameWithoutExtension.ifBlank { "rokid_frame" }

    private fun ingestFile(context: Context, file: File, source: String): Boolean {
        val frameId = frameIdOf(file)
        val payload = "rokid_file=${file.name} bytes=${file.length()}"
        Log.i(TAG, "ingestFile path=${file.absolutePath} source=$source exists=${file.exists()} size=${file.length()}")
        val appended = FrameStreamStore.append(
            context = context,
            frame = CapturedFrame(
                frameId = frameId,
                capturedAtEpochMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                source = source,
                payload = payload,
                previewImagePath = file.absolutePath,
            ),
        )
        Log.i(TAG, "append result=$appended frameId=$frameId")
        if (appended) PipelineStatsStore.incFrameCaptured(context)
        return appended
    }
}
