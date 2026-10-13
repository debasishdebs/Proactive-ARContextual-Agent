package com.rokid.context.data.frames

import android.content.Context
import android.util.Log
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.PipelineRecords
import java.io.File
import java.util.Locale

object RokidFrameInbox {
    private const val TAG = "RokidFrameInbox"
    private val supportedExt = setOf("jpg", "jpeg", "png", "webp")
    private const val INBOX_DIR = "rokid_inbox"
    private const val ARCHIVE_DIR = "rokid_archive"

    /** Every app-private folder (under filesDir) that holds frame JPEGs: the one list storage use and Delete all read. */
    val FRAME_DIRS: List<String> = listOf(ARCHIVE_DIR, INBOX_DIR)

    fun inboxDir(context: Context): File {
        return File(context.filesDir, INBOX_DIR)
    }

    fun archiveDir(context: Context): File {
        return File(context.filesDir, ARCHIVE_DIR)
    }

    /** [placeLabel]: a rough place for every frame of this run (Phase 7 C2; null unless Location is on and granted). */
    suspend fun ingestPending(context: Context, maxItems: Int = 8, placeLabel: String? = null): Int =
        ingestPendingIds(context, maxItems, placeLabel).size

    /** The ids of the frames this call appended (fix round 1: the summarizer job names exactly these). */
    suspend fun ingestPendingIds(context: Context, maxItems: Int = 8, placeLabel: String? = null): List<String> {
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

        val imported = mutableListOf<String>()
        for (file in candidates) {
            val archived = File(archive, "${System.currentTimeMillis()}_${file.name}")
            val moved = runCatching { file.renameTo(archived) }.getOrDefault(false)
            val sourceFile = if (moved) archived else file
            ingestFile(context, sourceFile, source = "rokid_glasses", placeLabel = placeLabel)?.let(imported::add)
        }
        return imported
    }

    /** The appended frame's id, or null when nothing was appended. */
    suspend fun ingestSinglePath(context: Context, absolutePath: String, source: String = "rokid_glasses", placeLabel: String? = null): String? {
        val file = File(absolutePath)
        if (!file.exists() || !file.isFile) return null
        if (extOf(file.name) !in supportedExt) return null
        return ingestFile(context, file, source, placeLabel)
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    // Full archived file name (<archiveMs>_rokid_<rxMs>_<id>): unique per file. Never truncate, ids only dedupe via insertIgnore.
    private fun frameIdOf(file: File): String = file.nameWithoutExtension.ifBlank { "rokid_frame" }

    private suspend fun ingestFile(context: Context, file: File, source: String, placeLabel: String? = null): String? {
        val frameId = frameIdOf(file)
        val payload = "rokid_file=${file.name} bytes=${file.length()}"
        Log.i(TAG, "ingestFile path=${file.absolutePath} source=$source exists=${file.exists()} size=${file.length()}")
        val appended = PipelineRecords.appendFrame(
            context,
            FrameEntity(
                id = frameId,
                ts = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                path = file.absolutePath,
                source = source,
                payload = payload,
                placeLabel = placeLabel,
            ),
        )
        Log.i(TAG, "append result=$appended frameId=$frameId")
        if (appended) PipelineStatsStore.incFrameCaptured(context)
        return frameId.takeIf { appended }
    }
}
