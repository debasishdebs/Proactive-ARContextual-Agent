package com.rokid.context.core.model

import java.util.Base64
import java.util.zip.CRC32

/** Max Base64 characters in one `pc_frame` chunk (spec §1.8; phase 0 saw no limit up to 256 KB, so 8× headroom). */
const val SAFE_CHUNK_CHARS = 32_768

/**
 * `FrameAck.reason` values. With `ok=false` ([CRC], [DECODE]) the glasses resend once, then drop the frame.
 * With `ok=true` ([DUPLICATE], [PAUSED_DROP], [TOO_OLD], [WIPED]) the glasses delete it like any delivered frame.
 */
object FrameAckReason {
    const val CRC = "crc"
    const val DECODE = "decode"
    const val DUPLICATE = "duplicate"
    const val PAUSED_DROP = "paused_drop"
    const val TOO_OLD = "too_old"
    /** Captured before the last Delete all started (spec 6.1.1): acked `ok=true` and never stored. */
    const val WIPED = "wiped_drop"
}

/** One `pc_frame` message: [meta] is the first Caps string (JSON), [chunk] the second (Base64 text, spec §1.5). */
data class FrameChunk(val meta: FrameMeta, val chunk: String)

sealed interface JoinResult {
    class Ok(val jpeg: ByteArray) : JoinResult
    data object BadBase64 : JoinResult
    data object CrcMismatch : JoinResult
}

/** Frame chunking shared by both apps (spec §1.8): Base64 of the whole JPEG, split at [SAFE_CHUNK_CHARS]. */
object FrameCodec {
    private val FRAME_ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** Frame ids end up in file names on both sides, so only this safe alphabet is accepted. */
    fun isValidFrameId(id: String): Boolean = FRAME_ID.matches(id)

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    fun split(
        frameId: String,
        jpeg: ByteArray,
        capturedAtMs: Long,
        luma: Int? = null,
        phash: String? = null,
        batteryPct: Int? = null,
        motion: MotionState = MotionState.UNKNOWN,
        dwell: Boolean = false,
        blur: Float? = null,
        dhash: String? = null,
        width: Int? = null,
        sampleSeq: Long? = null,
        sessionId: String? = null,
        markRef: String? = null,
        chunkChars: Int = SAFE_CHUNK_CHARS,
    ): List<FrameChunk> {
        require(isValidFrameId(frameId)) { "bad frame id" }
        require(jpeg.isNotEmpty()) { "empty frame" }
        require(chunkChars > 0) { "chunkChars must be positive" }
        val parts = Base64.getEncoder().encodeToString(jpeg).chunked(chunkChars)
        val crc = crc32(jpeg)
        return parts.mapIndexed { i, part ->
            FrameChunk(
                FrameMeta(frameId = frameId, chunkIdx = i, chunkCount = parts.size, crc32 = crc, capturedAtMs = capturedAtMs, luma = luma, phash = phash, batteryPct = batteryPct,
                    motion = motion, dwell = dwell, blur = blur, dhash = dhash, width = width, sampleSeq = sampleSeq, sessionId = sessionId, markRef = markRef),
                part,
            )
        }
    }

    /** Joins chunks in index order, decodes and checks the whole-frame CRC from [meta]. */
    fun join(meta: FrameMeta, chunks: List<String>): JoinResult {
        val bytes = try {
            Base64.getDecoder().decode(chunks.joinToString(""))
        } catch (e: IllegalArgumentException) {
            return JoinResult.BadBase64
        }
        if (bytes.isEmpty()) return JoinResult.BadBase64
        return if (crc32(bytes) == meta.crc32) JoinResult.Ok(bytes) else JoinResult.CrcMismatch
    }
}
