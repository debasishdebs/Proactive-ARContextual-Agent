package com.rokid.context.link

import com.rokid.context.core.model.FrameCodec
import java.io.File
import java.io.IOException

/**
 * Frame ids the phone has already stored (spec §1.8 dedupe). The newest [capacity] ids are kept on disk, so a resend
 * after a lost ack is never ingested twice, even across a restart. The glasses hold at most 200 frames, so 1 000 ids
 * covers every frame that can still be resent.
 */
class ReceivedFrameLedger(private val file: File, private val capacity: Int = 1_000) {
    private val ids = LinkedHashSet<String>()

    init {
        if (file.isFile) file.readLines().map { it.trim() }.filter { FrameCodec.isValidFrameId(it) }.forEach { ids += it }
        trim()
    }

    @Synchronized fun contains(frameId: String): Boolean = frameId in ids

    /** Records [frameId] durably (atomic rewrite) before the caller acks the frame; on failure the id is not kept. */
    @Synchronized fun add(frameId: String) {
        require(FrameCodec.isValidFrameId(frameId)) { "bad frame id" }
        if (!ids.add(frameId)) return
        try {
            trim()
            val dir = file.absoluteFile.parentFile ?: throw IOException("no ledger directory")
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(ids.joinToString("\n", postfix = "\n"))
            if (!tmp.renameTo(file)) throw IOException("ledger rename failed")
        } catch (e: IOException) {
            ids.remove(frameId)
            throw e
        }
    }

    private fun trim() { while (ids.size > capacity) ids.remove(ids.first()) }
}
