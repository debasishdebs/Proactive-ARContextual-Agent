package com.rokid.context.link

import com.rokid.context.core.model.FileDurability
import com.rokid.context.core.model.FrameCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Frame ids the phone has already stored (spec §1.8 dedupe). The newest [capacity] ids are kept on disk, so a resend
 * after a lost ack is never ingested twice, even across a restart. The glasses hold at most 200 frames, so 1 000 ids
 * covers every frame that can still be resent.
 */
class ReceivedFrameLedger(
    private val file: File,
    private val capacity: Int = 1_000,
    private val fsync: (File) -> Unit = { f -> FileOutputStream(f, true).use { it.fd.sync() } },
    private val fsyncDir: (File) -> Boolean = FileDurability::fsyncDir,
    private val log: (String) -> Unit = {},
) {
    private val ids = LinkedHashSet<String>()
    private var loaded = false
    private var fsyncWarned = false

    @Synchronized fun contains(frameId: String): Boolean { load(); return frameId in ids }

    /** Records [frameId] durably (temp, fsync, rename, dir fsync) before the caller acks the frame; on failure the id is not kept. */
    @Synchronized fun add(frameId: String) {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        load()
        if (!ids.add(frameId)) return
        try {
            trim()
            val dir = file.absoluteFile.parentFile ?: throw IOException("no ledger directory")
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(ids.joinToString("\n", postfix = "\n"))
            fsync(tmp)
            if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("ledger rename failed") }
            if (!fsyncDir(dir) && !fsyncWarned) { fsyncWarned = true; log("ledger directory not fsynced") }
        } catch (e: IOException) {
            ids.remove(frameId)
            throw e
        }
    }

    /**
     * On first use, never in the constructor (the hub is built on Main). A leftover temp is a crash mid-write and goes;
     * an unreadable file starts empty (the worst case is one duplicate frame ingested, never a crash).
     */
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            File(file.absoluteFile.parentFile, "${file.name}.tmp").delete()
            if (file.isFile) file.readLines().map { it.trim() }.filter { FrameCodec.isValidFrameId(it) }.forEach { ids += it }
        } catch (e: IOException) {
            ids.clear()
            log("ledger unreadable: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            ids.clear()
            log("ledger unreadable: ${e.javaClass.simpleName}")
        }
        trim()
    }

    private fun trim() { while (ids.size > capacity) ids.remove(ids.first()) }
}
