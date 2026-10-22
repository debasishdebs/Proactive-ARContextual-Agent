package com.rokid.context.capture.trace

/** Raised by every trace decoder. [code] is a short stable token (never raw data) that is safe to log and to put in a `TraceAck.reason`. */
class TraceFormatException(val code: String, detail: String = code) : IllegalArgumentException(detail)

/**
 * FROZEN (trace schema 1). The S73 transfer bounds, enforced on decode (and on encode, so a recorder cannot write what the phone refuses).
 * The five S73 limits are fixed constants; [DEFAULT] carries them and tests build a smaller copy to reach a limit cheaply.
 */
data class TraceBounds(
    /** Base64 characters in one `TraceChunk.b64`. */
    val maxB64Chars: Int = MAX_B64_CHARS,
    /** Raw (gunzipped) payload bytes of one chunk. Enforced by a streamed read, so a gzip bomb never inflates past it. */
    val maxRawBytes: Int = MAX_RAW_BYTES,
    /** Events in one chunk (checked against the header count before any event line is read). */
    val maxEventsPerChunk: Int = MAX_EVENTS_PER_CHUNK,
    /** Chunks in one trace (also bounds `seq`). */
    val maxChunks: Int = MAX_CHUNKS,
) {
    companion object {
        const val MAX_B64_CHARS = 90_000
        const val MAX_RAW_BYTES = 512 * 1024
        const val MAX_EVENTS_PER_CHUNK = 200_000
        const val MAX_CHUNKS = 4_096
        /** One whole trace (sum of raw payloads). 12 h at 100 sensor events/s is about 200 MB raw; the glasses disk cap (150 MB) holds it gzipped. */
        const val MAX_TRACE_RAW_BYTES = 256L * 1024 * 1024
        const val MAX_TRACE_EVENTS = 10_000_000L
        /** One event line, bytes. The longest legal line is under 200. */
        const val MAX_LINE_CHARS = 512
        /** The manifest document, bytes (it rides one `TraceChunk` of kind "manifest"). */
        const val MAX_MANIFEST_BYTES = 8_192
        /** JSON nesting allowed in the manifest (object, snapshot object, ring array, pair array). */
        const val MAX_MANIFEST_DEPTH = 5
        /** S65/S66: decisions inside the first 10 s after the (clean) start are discarded by the simulator. */
        const val PRE_ROLL_MS = 10_000L
        const val MAX_MS = 9_007_199_254_740_991L   // 2^53 - 1: every time field is a plain JSON integer any parser reads exactly
        val DEFAULT = TraceBounds()

        /** S73: `^[a-z0-9-]{8,64}$` (full match, so a trailing newline is not accepted). */
        private val TRACE_ID = Regex("^[a-z0-9-]{8,64}$")
        fun isTraceId(s: String): Boolean = TRACE_ID.matches(s)
    }
}
