package com.rokid.context.data.frames

import android.content.Context
import android.util.Log
import com.rokid.context.data.runtime.PipelineStatsStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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

    suspend fun ingestPending(
        context: Context,
        maxItems: Int = 8,
        nowMs: () -> Long = System::currentTimeMillis,
        appendFrame: suspend (Context, FrameEntity) -> Boolean = PipelineRecords::appendFrame,
    ): Int {
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

        var imported = 0
        for (file in candidates) {
            // Review Focus 3: a Delete all that starts while this file is ingested bumps the generation; its row and file go.
            val generation = MemoryWipe.generation.get()
            val archived = File(archive, "${nowMs()}_${file.name}")
            val moved = runCatching { file.renameTo(archived) }.getOrDefault(false)
            val sourceFile = if (moved) archived else file
            val result = try {
                ingestFile(context, sourceFile, source = "rokid_glasses", appendFrame)
            } catch (e: CancellationException) {
                // Fix round 1 I4: a drain cancelled mid-insert returns the file unless its row landed, so it stays eligible.
                withContext(NonCancellable) {
                    if (moved && MemoryGraph.get(context).frames.byIds(listOf(frameIdOf(sourceFile))).isEmpty()) archived.renameTo(file)
                }
                throw e
            }
            when (result) {
                InboxResult.STORED -> if (MemoryWipe.generation.get() != generation) {
                    MemoryGraph.get(context).frames.deleteByIds(listOf(frameIdOf(sourceFile)))
                    sourceFile.delete()
                    Log.i(TAG, "frame dropped: Delete all ran during its ingest")
                } else {
                    imported += 1
                }
                InboxResult.DUPLICATE -> Unit
                InboxResult.FAILED -> if (moved && !archived.renameTo(file)) Log.w(TAG, "failed ingest not returned to the inbox")
            }
        }
        return imported
    }

    suspend fun ingestSinglePath(context: Context, absolutePath: String, source: String = "rokid_glasses"): Boolean {
        val file = File(absolutePath)
        if (!file.exists() || !file.isFile) return false
        if (extOf(file.name) !in supportedExt) return false
        return ingestFile(context, file, source) == InboxResult.STORED
    }

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    // Full archived file name (<archiveMs>_rokid_<rxMs>_<id>): unique per file. Never truncate, ids only dedupe via insertIgnore.
    private fun frameIdOf(file: File): String = file.nameWithoutExtension.ifBlank { "rokid_frame" }

    internal suspend fun ingestFile(
        context: Context,
        file: File,
        source: String,
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
