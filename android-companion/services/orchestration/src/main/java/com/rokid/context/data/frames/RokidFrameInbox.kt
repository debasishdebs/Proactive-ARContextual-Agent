package com.rokid.context.data.frames

import android.content.Context
import android.util.Log
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.WipeFence
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One inbox file's ingest: [FAILED] (threw) goes back to the inbox for the next drain; a [DUPLICATE] stays archived. */
internal enum class InboxResult { STORED, DUPLICATE, FAILED }

object RokidFrameInbox {
    private const val TAG = "RokidFrameInbox"
    private val supportedExt = setOf("jpg", "jpeg", "png", "webp")
    private const val INBOX_DIR = "rokid_inbox"
    private const val ARCHIVE_DIR = "rokid_archive"

    /**
     * Every app-private folder (under filesDir) that holds frame JPEGs: the one list storage use and Delete all read.
     * Inbox first: an ingest moves inbox to archive, so a file moved during Delete all's walk is still seen in the archive.
     */
    val FRAME_DIRS: List<String> = listOf(INBOX_DIR, ARCHIVE_DIR)

    fun inboxDir(context: Context): File {
        return File(context.filesDir, INBOX_DIR)
    }

    fun archiveDir(context: Context): File {
        return File(context.filesDir, ARCHIVE_DIR)
    }

    /**
     * Deletes `.part` files (an atomic write the process died in) older than [minAgeMs]; a fresh one may be a write in
     * progress. Returns how many went.
     */
    fun sweepOrphanParts(context: Context, nowMs: Long = System.currentTimeMillis(), minAgeMs: Long = 60_000): Int =
        inboxDir(context).listFiles().orEmpty().count { f ->
            f.isFile && f.name.endsWith(".part") && nowMs - f.lastModified() >= minAgeMs && f.delete()
        }

    /** [placeLabel]: a rough place for every frame of this run (Phase 7 C2; null unless Location is on and granted). */
    suspend fun ingestPending(
        context: Context,
        maxItems: Int = 8,
        placeLabel: String? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        appendFrame: suspend (Context, FrameEntity) -> Boolean = PipelineRecords::appendFrame,
    ): Int = ingestPendingIds(context, maxItems, placeLabel, nowMs, appendFrame).size

    /** The ids of the frames this call appended (fix round 1: the summarizer job names exactly these). */
    suspend fun ingestPendingIds(
        context: Context,
        maxItems: Int = 8,
        placeLabel: String? = null,
        nowMs: () -> Long = System::currentTimeMillis,
        appendFrame: suspend (Context, FrameEntity) -> Boolean = PipelineRecords::appendFrame,
    ): List<String> {
        val swept = sweepOrphanParts(context, nowMs())
        if (swept > 0) Log.i(TAG, "swept orphan parts=$swept")
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
            // Review Focus 3: a Delete all that starts while this file is ingested bumps the generation; its row and file go.
            val generation = MemoryWipe.generation.get()
            val archived = File(archive, "${nowMs()}_${file.name}")
            val moved = runCatching { file.renameTo(archived) }.getOrDefault(false)
            val sourceFile = if (moved) archived else file
            val result = try {
                ingestFile(context, sourceFile, source = "rokid_glasses", placeLabel, appendFrame)
            } catch (e: CancellationException) {
                // Fix round 1/2 I4: a drain cancelled mid-insert keeps the file eligible unless its row landed. If a Delete
                // all started meanwhile, the file (and any row) goes instead: it must not come back after the wipe.
                withContext(NonCancellable) { afterCancelledIngest(context, generation, moved, sourceFile, file) }
                throw e
            }
            when (result) {
                InboxResult.STORED -> if (MemoryWipe.generation.get() != generation) {
                    MemoryGraph.get(context).frames.deleteByIds(listOf(frameIdOf(sourceFile)))
                    sourceFile.delete()
                    Log.i(TAG, "frame dropped: Delete all ran during its ingest")
                } else {
                    imported += frameIdOf(sourceFile)
                }
                InboxResult.DUPLICATE -> Unit
                // Fix round 3: same rule as a cancelled ingest. A Delete all since it began deletes the file, else it goes back.
                InboxResult.FAILED -> if (MemoryWipe.generation.get() != generation) {
                    sourceFile.delete()
                    Log.i(TAG, "failed frame dropped: Delete all ran during its ingest")
                } else if (moved && !WipeFence.mutex.withLock { archived.renameTo(file) }) {
                    Log.w(TAG, "failed ingest not returned to the inbox")
                }
            }
        }
        return imported
    }

    private suspend fun afterCancelledIngest(context: Context, generation: Long, moved: Boolean, sourceFile: File, original: File) {
        val id = frameIdOf(sourceFile)
        if (MemoryWipe.generation.get() != generation) {
            try { MemoryGraph.get(context).frames.deleteByIds(listOf(id)) } catch (e: Exception) { Log.w(TAG, "row not removed: ${e.javaClass.simpleName}") }
            sourceFile.delete()
            return
        }
        if (!moved) return
        // A throwing check keeps the file (worst case: one duplicate ingest), never loses it.
        val hasRow = try { MemoryGraph.get(context).frames.byIds(listOf(id)).isNotEmpty() } catch (e: Exception) { false }
        if (!hasRow) WipeFence.mutex.withLock { sourceFile.renameTo(original) }
    }

    /** The appended frame's id, or null when nothing was appended. */
    suspend fun ingestSinglePath(context: Context, absolutePath: String, source: String = "rokid_glasses", placeLabel: String? = null): String? {
        val file = File(absolutePath)
        if (!file.exists() || !file.isFile) return null
        if (extOf(file.name) !in supportedExt) return null
        return frameIdOf(file).takeIf { ingestFile(context, file, source, placeLabel) == InboxResult.STORED }
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    // Full archived file name (<archiveMs>_rokid_<rxMs>_<id>): unique per file. Never truncate, ids only dedupe via insertIgnore.
    private fun frameIdOf(file: File): String = file.nameWithoutExtension.ifBlank { "rokid_frame" }

    internal suspend fun ingestFile(
        context: Context,
        file: File,
        source: String,
        placeLabel: String? = null,
        appendFrame: suspend (Context, FrameEntity) -> Boolean = PipelineRecords::appendFrame,
    ): InboxResult {
        val frameId = frameIdOf(file)
        val payload = "rokid_file=${file.name} bytes=${file.length()}"
        Log.i(TAG, "ingestFile path=${file.absolutePath} source=$source exists=${file.exists()} size=${file.length()}")
        val appended = try {
            appendFrame(
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "ingest failed frameId=$frameId: ${e.javaClass.simpleName}")
            return InboxResult.FAILED
        }
        Log.i(TAG, "append result=$appended frameId=$frameId")
        if (appended) PipelineStatsStore.incFrameCaptured(context)
        // appendFrame's false is a duplicate id or a file that vanished: neither is worth a retry.
        return if (appended) InboxResult.STORED else InboxResult.DUPLICATE
    }
}
