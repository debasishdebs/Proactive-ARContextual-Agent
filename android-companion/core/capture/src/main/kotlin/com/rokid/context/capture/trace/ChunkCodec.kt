package com.rokid.context.capture.trace

import com.rokid.context.core.model.TraceChunk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** An encoded chunk, ready for a `TraceChunk`: [sha256] is over the raw payload, [b64] is base64 of its gzip. */
class EncodedChunk(val traceId: String, val seq: Int, val eventCount: Int, val rawBytes: Int, val sha256: String, val b64: String, val firstTMs: Long, val lastTMs: Long)

/** A verified chunk (events decoded, checksum and bounds checked). */
/** [count] is `TraceChunk.count` when decoded from a `TraceChunk`, null when decoded from parts. */
class DecodedChunk(val traceId: String, val seq: Int, val events: List<TraceEvent>, val rawBytes: Int, val sha256: String, val count: Int? = null) {
    val firstTMs: Long get() = events.first().tMs
    val lastTMs: Long get() = events.last().tMs
}

/**
 * FROZEN (trace schema 1). The chunk wire format.
 *
 * - **Payload** (the raw bytes, UTF-8/ASCII): a header line `["PCT1","<traceId>",<seq>,<eventCount>]` then `eventCount` event lines
 *   ([EventCodec]), every line ended by `\n` (no `\r`, no blank line, no trailing bytes). The header binds the payload to its trace and
 *   position, so chunks cannot be swapped between traces or reordered silently.
 * - **Chunk sha256**: lower-case hex SHA-256 of the raw payload (deterministic, independent of the gzip implementation).
 * - **b64**: standard base64 (RFC 4648, padded, no line breaks) of the gzip of the payload.
 * - **`TraceChunk` mapping** (kind `"chunk"`): `count` = number of chunks in the whole trace, `seq` in `[0, count)`, `sha256` and `b64` as above.
 * - **Bounds** ([TraceBounds]): b64 <= 90,000 chars, raw <= 512 KiB (a streamed gunzip limit), <= 200,000 events, seq < 4,096.
 * - **Time**: event `tMs` is non-decreasing inside a chunk (and, checked by [TraceAssembler], across chunks). Sensor time `sMs` is not ordered.
 * - **Real chunk limit (M7):** the binding bound is the 90,000 char b64, not 512 KiB. IMU lines gzip roughly 3:1, so 90,000 chars is about 67 KB gzip,
 *   about 200 KB raw. [encode] throws `b64_too_long` AFTER the work is done, so the recorder (Task 16) must flush at a raw target of about 150 KB (the
 *   64 KB flush of round 1 fits) and, on `b64_too_long`, split the event list in two (first half keeps the seq, the rest follows) and retry.
 *   4,096 chunks x 64 KB = 256 MiB = `MAX_TRACE_RAW_BYTES`, so 12 h at 100 events/s (about 195 MB) fits.
 * - **Padding (M5):** the base64 must be padded (length a multiple of 4); URL-safe characters and whitespace are refused.
 *
 * Decode never throws anything but [TraceFormatException] (returned in a [Result]); its `code` is one of
 * bad_trace_id, seq, chunk_count, kind, sha_invalid, b64_too_long, b64_invalid, gzip_invalid, gunzip_too_big, sha_mismatch, bad_payload,
 * bad_header, header_mismatch, too_many_events, line_count, bad_event, not_monotonic.
 */
object ChunkCodec {
    const val MAGIC = "PCT1"
    const val KIND = "chunk"
    private val SHA = Regex("[0-9a-f]{64}")

    fun payload(traceId: String, seq: Int, events: List<TraceEvent>, bounds: TraceBounds = TraceBounds.DEFAULT): ByteArray {
        if (!TraceBounds.isTraceId(traceId)) throw TraceFormatException("bad_trace_id")
        if (seq !in 0 until bounds.maxChunks) throw TraceFormatException("seq")
        if (events.isEmpty()) throw TraceFormatException("bad_header")
        if (events.size > bounds.maxEventsPerChunk) throw TraceFormatException("too_many_events")
        val sb = StringBuilder(events.size * 48 + 64)
        sb.append("[\"").append(MAGIC).append("\",\"").append(traceId).append("\",").append(seq).append(',').append(events.size).append("]\n")
        var prev = 0L
        for (e in events) {
            if (e.tMs < prev) throw TraceFormatException("not_monotonic")
            prev = e.tMs
            sb.append(EventCodec.encode(e)).append('\n')
        }
        val raw = sb.toString().toByteArray(Charsets.UTF_8)
        if (raw.size > bounds.maxRawBytes) throw TraceFormatException("gunzip_too_big")
        return raw
    }

    fun encode(traceId: String, seq: Int, events: List<TraceEvent>, bounds: TraceBounds = TraceBounds.DEFAULT): EncodedChunk {
        val raw = payload(traceId, seq, events, bounds)
        val bo = ByteArrayOutputStream(raw.size / 3 + 64)
        GZIPOutputStream(bo).use { it.write(raw) }
        val b64 = Base64.getEncoder().encodeToString(bo.toByteArray())
        if (b64.length > bounds.maxB64Chars) throw TraceFormatException("b64_too_long")
        return EncodedChunk(traceId, seq, events.size, raw.size, sha256Hex(raw), b64, events.first().tMs, events.last().tMs)
    }

    fun decode(c: TraceChunk, bounds: TraceBounds = TraceBounds.DEFAULT): Result<DecodedChunk> = runCatching {
        if (c.kind != KIND) throw TraceFormatException("kind")
        if (c.count !in 1..bounds.maxChunks) throw TraceFormatException("chunk_count")
        if (c.seq !in 0 until c.count) throw TraceFormatException("seq")
        decodeOrThrow(c.traceId, c.seq, c.sha256, c.b64, bounds).let { DecodedChunk(it.traceId, it.seq, it.events, it.rawBytes, it.sha256, c.count) }
    }.recoverFormat()

    fun decode(traceId: String, seq: Int, sha256: String, b64: String, bounds: TraceBounds = TraceBounds.DEFAULT): Result<DecodedChunk> =
        runCatching { decodeOrThrow(traceId, seq, sha256, b64, bounds) }.recoverFormat()

    private fun <T> Result<T>.recoverFormat(): Result<T> = exceptionOrNull().let { e ->
        // Anything unexpected is still reported as a format failure (never a crash on hostile input).
        if (e == null || e is TraceFormatException) this else Result.failure(TraceFormatException("bad_payload"))
    }

    private fun decodeOrThrow(traceId: String, seq: Int, sha256: String, b64: String, bounds: TraceBounds): DecodedChunk {
        if (!TraceBounds.isTraceId(traceId)) throw TraceFormatException("bad_trace_id")
        if (seq !in 0 until bounds.maxChunks) throw TraceFormatException("seq")
        if (!SHA.matches(sha256)) throw TraceFormatException("sha_invalid")
        if (b64.length > bounds.maxB64Chars) throw TraceFormatException("b64_too_long")
        if (b64.length % 4 != 0) throw TraceFormatException("b64_invalid") // padded base64 only (the JDK decoder would accept unpadded input)
        val gz = try { Base64.getDecoder().decode(b64) } catch (e: IllegalArgumentException) { throw TraceFormatException("b64_invalid") }
        val raw = gunzipLimited(gz, bounds.maxRawBytes)
        if (sha256Hex(raw) != sha256) throw TraceFormatException("sha_mismatch")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) { throw TraceFormatException("bad_payload") }
        if (text.isEmpty()) throw TraceFormatException("bad_header")
        if (text.indexOf('\r') >= 0 || !text.endsWith("\n")) throw TraceFormatException("bad_payload")
        val lines = text.substring(0, text.length - 1).split('\n')
        if (lines.any { it.isEmpty() }) throw TraceFormatException("bad_payload")
        val count = parseHeader(lines[0], traceId, seq, bounds)
        if (lines.size - 1 != count) throw TraceFormatException("line_count")
        val events = ArrayList<TraceEvent>(count)
        var prev = 0L
        for (i in 1 until lines.size) {
            val e = try { EventCodec.decode(lines[i]) } catch (x: TraceFormatException) { throw TraceFormatException("bad_event") }
            if (e.tMs < prev) throw TraceFormatException("not_monotonic")
            prev = e.tMs
            events.add(e)
        }
        return DecodedChunk(traceId, seq, events, raw.size, sha256)
    }

    private fun parseHeader(line: String, traceId: String, seq: Int, bounds: TraceBounds): Int {
        if (line.length > TraceBounds.MAX_LINE_CHARS) throw TraceFormatException("bad_header")
        val a = try { Json.parseToJsonElement(line) as? JsonArray } catch (e: Exception) { null } ?: throw TraceFormatException("bad_header")
        val p = a.map { it as? JsonPrimitive }
        if (p.size != 4 || p.any { it == null }) throw TraceFormatException("bad_header")
        val magic = p[0]!!; val id = p[1]!!; val s = p[2]!!; val n = p[3]!!
        if (!magic.isString || magic.content != MAGIC || !id.isString || s.isString || n.isString) throw TraceFormatException("bad_header")
        val sv = s.content.toIntOrNull(); val nv = n.content.toLongOrNull()
        if (sv == null || nv == null) throw TraceFormatException("bad_header")
        if (nv > bounds.maxEventsPerChunk) throw TraceFormatException("too_many_events")
        if (nv < 1) throw TraceFormatException("bad_header")
        if (id.content != traceId || sv != seq) throw TraceFormatException("header_mismatch")
        if (line != "[\"$MAGIC\",\"$traceId\",$seq,$nv]") throw TraceFormatException("bad_header")
        return nv.toInt()
    }

    /** Streamed gunzip: aborts as soon as more than [limit] bytes would be produced, so a bomb never inflates in memory. */
    internal fun gunzipLimited(gz: ByteArray, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, gz.size * 4 + 64))
        val buf = ByteArray(8192)
        try {
            GZIPInputStream(ByteArrayInputStream(gz), 8192).use { zin ->
                while (true) {
                    val n = zin.read(buf)
                    if (n < 0) break
                    if (out.size().toLong() + n > limit) throw TraceFormatException("gunzip_too_big")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: TraceFormatException) { throw e } catch (e: IOException) { throw TraceFormatException("gzip_invalid") }
        return out.toByteArray()
    }

    internal fun sha256Hex(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
}
