package com.rokid.context.ai.embed.siglip

import java.io.File
import java.util.Locale
import java.util.PriorityQueue

/**
 * Pure-Kotlin tokenizer for google/siglip2-base-patch16-224 (and the onnx-community export): SentencePiece BPE,
 * 256,000 pieces, byte fallback, identity normaliser, no dummy prefix.
 *
 * Reads the 4.2 MB `tokenizer.model` (SentencePiece protobuf). That file is DATA: it is downloaded with the model
 * and is NOT committed to the repo. Reference file used for the parity goldens (re-verify by hand):
 *   url    https://huggingface.co/google/siglip2-base-patch16-224/resolve/75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2/tokenizer.model
 *   bytes  4,241,003
 *   sha256 61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2
 * Goldens were produced with transformers' GemmaTokenizer (slow and fast agree on all strings) using
 * `tok(text.lower(), padding="max_length", max_length=64, truncation=True)`.
 *
 * Algorithm (matches SentencePiece bpe_model.cc plus the HF added-token / EOS / padding behaviour):
 *  1. lowercase (SigLIP 2 was trained on lowercased text; HF does NOT do it for you), `' '` -> `▁` (U+2581);
 *  2. split out "added tokens" (user-defined pieces such as `<mask>`, runs of `▁` / tab / newline, plus the control
 *     pieces `<pad> <eos> <bos> <unk>` typed literally), leftmost-longest, each mapped straight to its id;
 *  3. every remaining segment: split into code points, then repeatedly merge the adjacent pair whose concatenation
 *     is a NORMAL piece with the best score (ties: leftmost) until none is left;
 *  4. symbols not in the vocabulary (only possible for single characters) become their UTF-8 `<0xXX>` byte pieces;
 *  5. at most [maxLength] - 1 tokens are kept, then EOS (id 1) is appended (it survives truncation), then right
 *     padding with `<pad>` (id 0) to exactly [maxLength]. No BOS.
 *
 * Memory: the vocabulary is loaded lazily on first [encode] into one UTF-8 blob + an open-addressing int table
 * (about 9 MB), not a HashMap<String, Int>. Call [release] to drop it (for example when the text tower closes).
 *
 * Known deviation: unpaired UTF-16 surrogates are replaced with U+FFFD before encoding.
 */
class SigLipTokenizer private constructor(
    private val source: () -> ByteArray,
    override val maxLength: Int = DEFAULT_MAX_LENGTH,
) : TextTokenizer {

    constructor(modelFile: File, maxLength: Int = DEFAULT_MAX_LENGTH) : this({ modelFile.readBytes() }, maxLength)

    init { require(maxLength >= 2) { "maxLength must be >= 2" } }

    @Volatile private var vocab: Vocab? = null

    private fun vocab(): Vocab = vocab ?: synchronized(this) {
        vocab ?: Vocab.parse(source()).also { vocab = it }
    }

    /** Drop the vocabulary; the next [encode] reloads it from the file. */
    fun release() { synchronized(this) { vocab = null } }

    override fun encode(text: String): EncodedText {
        val v = vocab()
        val limit = maxLength - 1
        val content = IntList(limit + 1)
        val prepared = sanitize(text.lowercase(Locale.ROOT)).replace(' ', SPACE)
        var segStart = 0
        var i = 0
        while (i < prepared.length && content.size < limit) {
            val added = v.matchAdded(prepared, i)
            if (added != null) {
                if (i > segStart) encodeSegment(v, prepared.substring(segStart, i), content, limit)
                content.add(added.id)
                i += added.length
                segStart = i
            } else {
                i++
            }
        }
        if (segStart < prepared.length && content.size < limit) {
            encodeSegment(v, prepared.substring(segStart), content, limit)
        }
        val ids = LongArray(maxLength)
        val mask = LongArray(maxLength)
        val n = minOf(content.size, limit)
        for (k in 0 until n) { ids[k] = content[k].toLong(); mask[k] = 1L }
        ids[n] = EOS_ID.toLong(); mask[n] = 1L
        return EncodedText(ids, mask) // remaining positions: 0 = <pad>, mask 0
    }

    // ---- BPE over one segment -------------------------------------------------------------------------------------

    private class Merge(val left: Int, val right: Int, val score: Float, val size: Int)

    private fun encodeSegment(v: Vocab, seg: String, out: IntList, limit: Int) {
        val b = seg.toByteArray(Charsets.UTF_8)
        val n = b.size
        if (n == 0) return
        val start = IntArray(n); val len = IntArray(n); val prev = IntArray(n); val next = IntArray(n)
        var count = 0
        var p = 0
        while (p < n) {
            val l = minOf(utf8Len(b[p]), n - p)
            start[count] = p; len[count] = l; prev[count] = count - 1; next[count] = count + 1
            count++; p += l
        }
        next[count - 1] = -1
        val agenda = PriorityQueue<Merge>(maxOf(count, 4)) { x, y ->
            val c = y.score.compareTo(x.score) // higher score first
            if (c != 0) c else x.left.compareTo(y.left) // then leftmost
        }
        fun tryAdd(l: Int, r: Int) {
            if (l < 0 || r < 0) return
            val size = len[l] + len[r]
            val id = v.lookup(b, start[l], size)
            if (id >= 0) agenda.add(Merge(l, r, v.scores[id], size))
        }
        for (k in 1 until count) tryAdd(k - 1, k)
        while (agenda.isNotEmpty()) {
            val m = agenda.poll()
            val l = m.left; val r = m.right
            if (len[l] == 0 || len[r] == 0 || len[l] + len[r] != m.size) continue
            len[l] += len[r]; len[r] = 0
            next[l] = next[r]
            if (next[r] != -1) prev[next[r]] = l
            tryAdd(prev[l], l)
            tryAdd(l, next[l])
        }
        var s = 0
        while (s != -1 && out.size < limit) {
            val id = v.lookup(b, start[s], len[s])
            if (id >= 0) {
                out.add(id)
            } else {
                for (k in start[s] until start[s] + len[s]) {
                    out.add(if (v.byteBase >= 0) v.byteBase + (b[k].toInt() and 0xFF) else UNK_ID)
                    if (out.size >= limit) break
                }
            }
            s = next[s]
        }
    }

    // ---- vocabulary -----------------------------------------------------------------------------------------------

    private class Added(val id: Int, val length: Int)

    private class Vocab(
        val blob: ByteArray, val offsets: IntArray, val scores: FloatArray,
        val table: IntArray, val byteBase: Int,
        val addedByFirst: Map<Char, List<Pair<String, Int>>>,
    ) {
        fun lookup(b: ByteArray, off: Int, len: Int): Int {
            var h = -0x7ee3623b // FNV-1a 32
            for (k in off until off + len) { h = (h xor (b[k].toInt() and 0xFF)) * 0x01000193 }
            val mask = table.size - 1
            var slot = (h xor (h ushr 15)) and mask
            while (true) {
                val e = table[slot]
                if (e < 0) return -1
                val o = offsets[e]
                if (offsets[e + 1] - o == len && sameBytes(b, off, o, len)) return e
                slot = (slot + 1) and mask
            }
        }

        private fun sameBytes(b: ByteArray, off: Int, blobOff: Int, len: Int): Boolean {
            for (k in 0 until len) if (b[off + k] != blob[blobOff + k]) return false
            return true
        }

        fun matchAdded(s: String, at: Int): Added? {
            val cands = addedByFirst[s[at]] ?: return null
            for ((piece, id) in cands) { // sorted longest first
                if (s.startsWith(piece, at)) return Added(id, piece.length)
            }
            return null
        }

        companion object {
            private const val NORMAL = 1; private const val UNKNOWN = 2; private const val CONTROL = 3
            private const val USER_DEFINED = 4; private const val BYTE = 6

            fun parse(model: ByteArray): Vocab {
                val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
                val sc = ArrayList<Float>(); val types = ArrayList<Int>()
                var normalizerName = ""; var charsmapSize = 0
                val r = Reader(model, 0, model.size)
                while (!r.eof()) {
                    val tag = r.varint().toInt()
                    val field = tag ushr 3; val wire = tag and 7
                    if (field == 1 && wire == 2) {
                        val len = r.varint().toInt(); val sub = Reader(model, r.pos, r.pos + len); r.pos += len
                        var ps = 0; var pe = 0; var score = 0f; var type = NORMAL
                        while (!sub.eof()) {
                            val t = sub.varint().toInt()
                            when (t) {
                                (1 shl 3) or 2 -> { val l = sub.varint().toInt(); ps = sub.pos; pe = ps + l; sub.pos = pe }
                                (2 shl 3) or 5 -> { score = java.lang.Float.intBitsToFloat(sub.fixed32()) }
                                (3 shl 3) or 0 -> type = sub.varint().toInt()
                                else -> sub.skip(t and 7)
                            }
                        }
                        starts.add(ps); ends.add(pe); sc.add(score); types.add(type)
                    } else if (field == 3 && wire == 2) { // normalizer_spec
                        val len = r.varint().toInt(); val sub = Reader(model, r.pos, r.pos + len); r.pos += len
                        while (!sub.eof()) {
                            val t = sub.varint().toInt()
                            when (t) {
                                (1 shl 3) or 2 -> { val l = sub.varint().toInt(); normalizerName = String(model, sub.pos, l, Charsets.UTF_8); sub.pos += l }
                                (2 shl 3) or 2 -> { val l = sub.varint().toInt(); charsmapSize = l; sub.pos += l }
                                else -> sub.skip(t and 7)
                            }
                        }
                    } else r.skip(wire)
                }
                val n = starts.size
                require(n > 0) { "not a SentencePiece model: no pieces" }
                // The algorithm below implements only the identity normaliser (what SigLIP 2 uses).
                require(charsmapSize == 0 && (normalizerName == "identity" || normalizerName.isEmpty())) {
                    "unsupported SentencePiece normaliser '$normalizerName' (charsmap $charsmapSize bytes)"
                }
                val offsets = IntArray(n + 1)
                var total = 0
                for (i in 0 until n) { offsets[i] = total; total += ends[i] - starts[i] }
                offsets[n] = total
                val blob = ByteArray(total)
                for (i in 0 until n) System.arraycopy(model, starts[i], blob, offsets[i], ends[i] - starts[i])
                val scores = FloatArray(n) { sc[it] }
                var size = 1
                while (size < n * 2) size = size shl 1
                val table = IntArray(size) { -1 }
                var byteBase = -1
                val added = HashMap<Char, ArrayList<Pair<String, Int>>>()
                for (i in 0 until n) {
                    when (types[i]) {
                        NORMAL -> {
                            val len = offsets[i + 1] - offsets[i]
                            val h = hashBlob(blob, offsets[i], len)
                            var slot = (h xor (h ushr 15)) and (size - 1)
                            while (table[slot] >= 0) slot = (slot + 1) and (size - 1)
                            table[slot] = i
                        }
                        BYTE -> {
                            val s = String(blob, offsets[i], offsets[i + 1] - offsets[i], Charsets.UTF_8)
                            if (s == "<0x00>") byteBase = i
                        }
                        USER_DEFINED, CONTROL, UNKNOWN -> {
                            val s = String(blob, offsets[i], offsets[i + 1] - offsets[i], Charsets.UTF_8)
                            if (s.isNotEmpty()) added.getOrPut(s[0]) { ArrayList() }.add(s to i)
                        }
                    }
                }
                if (byteBase >= 0) { // the 256 byte pieces must be contiguous and in order
                    for (k in 0 until 256) {
                        val s = String(blob, offsets[byteBase + k], offsets[byteBase + k + 1] - offsets[byteBase + k], Charsets.UTF_8)
                        require(s == "<0x%02X>".format(k)) { "byte pieces not contiguous at $k" }
                    }
                }
                val addedSorted = added.mapValues { e -> e.value.sortedByDescending { it.first.length } }
                return Vocab(blob, offsets, scores, table, byteBase, addedSorted)
            }

            private fun hashBlob(b: ByteArray, off: Int, len: Int): Int {
                var h = -0x7ee3623b
                for (k in off until off + len) { h = (h xor (b[k].toInt() and 0xFF)) * 0x01000193 }
                return h
            }
        }
    }

    private class Reader(val b: ByteArray, var pos: Int, val end: Int) {
        fun eof() = pos >= end
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val x = b[pos++].toInt()
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        fun fixed32(): Int {
            val r = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return r
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val l = varint().toInt(); pos += l }
                5 -> pos += 4
                else -> error("bad protobuf wire type $wire")
            }
        }
    }

    private class IntList(cap: Int) {
        private var a = IntArray(maxOf(cap, 8)); var size = 0; private set
        fun add(x: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = x }
        operator fun get(i: Int) = a[i]
    }

    companion object {
        const val DEFAULT_MAX_LENGTH = 64
        const val PAD_ID = 0
        const val EOS_ID = 1
        const val BOS_ID = 2
        const val UNK_ID = 3
        private const val SPACE = '▁'

        /** Builds a tokenizer from bytes already in memory (tests, or a model read by the caller). */
        fun fromBytes(model: ByteArray, maxLength: Int = DEFAULT_MAX_LENGTH) = SigLipTokenizer({ model }, maxLength)

        private fun utf8Len(first: Byte): Int {
            val x = first.toInt() and 0xFF
            return when { x < 0x80 -> 1; x < 0xE0 -> 2; x < 0xF0 -> 3; else -> 4 }
        }

        private fun sanitize(s: String): String {
            var bad = false
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { i += 2; continue }
                if (Character.isSurrogate(c)) { bad = true; break }
                i++
            }
            if (!bad) return s
            val sb = StringBuilder(s.length)
            i = 0
            while (i < s.length) {
                val c = s[i]
                if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    sb.append(c).append(s[i + 1]); i += 2
                } else {
                    sb.append(if (Character.isSurrogate(c)) '�' else c); i++
                }
            }
            return sb.toString()
        }
    }
}
