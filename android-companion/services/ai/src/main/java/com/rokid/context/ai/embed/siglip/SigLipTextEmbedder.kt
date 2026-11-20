package com.rokid.context.ai.embed.siglip

import com.rokid.context.ai.local.OrtTextSession
import com.rokid.context.ai.local.TextSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * SigLIP 2 B/16-224 TEXT tower on ONNX Runtime (milestone X of plan 2026-11-20-siglip2-integration-spike). Standalone: NOT wired into LocalModels, the catalog, EmbeddingSpec
 * or any production flow. Text -> [SigLipTokenizer] (lowercase, SentencePiece BPE, EOS, right pad to 64) -> int64 `input_ids` [1,64] -> `pooler_output` -> L2 -> 768 floats.
 *
 * Model file verified in this milestone (NOT committed to git; a human must re-check the hash):
 *   url    https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/text_model_quantized.onnx
 *   commit ba1f3b0843f24bc5417d38e19c37b287d719b2f4
 *   bytes  283,438,275
 *   sha256 3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400  (computed locally after download; equals the LFS oid in the spike)
 *   graph  input `input_ids` int64 [batch,seq] ONLY (no attention_mask); outputs `last_hidden_state` [b,seq,768] (NOT an embedding), `pooler_output` [b,768]
 *
 * Catalog fields a later wiring step needs: see [SigLipImageTextEmbedder].
 *
 * One text per ORT run by default ([BATCH] = 1): the int8 export quantises activations per tensor, so a text's vector depends on its batchmates
 * (measured against python onnxruntime on 34 strings: batches of 8 vs one at a time, cosine min 0.9955 / mean 0.9985; one batch of 34, min 0.983 / mean 0.997).
 * Lazy: the session and the tokenizer vocabulary load on the first embed; [close] drops both (the next embed reloads them).
 * Failures propagate to the caller (who treats any failure as "no score").
 */
class SigLipTextEmbedder(
    private val modelFile: File,
    private val tokenizer: TextTokenizer,
    val variant: String = "text_quantized",
    private val batchSize: Int = BATCH,
    /** (path, outputName, width) -> session; the default builds the real ORT session. Exists so a test can see what the default path asks for. */
    private val ortFactory: (String, String, Int) -> TextSession = { p, o, w -> OrtTextSession(p, o, w) },
    private val sessionFactory: (String) -> TextSession = { ortFactory(it, OUTPUT_NAME, DIM) },
) : AutoCloseable {
    init { require(batchSize >= 1) { "batchSize" } }

    val dim: Int get() = DIM

    /** The text half of the space identity; [SigLipImageTextEmbedder.spaceId] combines it with the vision half. */
    val spaceId: String get() = fingerprint(variant, tokenizer.maxLength)

    private val lock = Mutex()
    private var session: TextSession? = null

    val isLoaded: Boolean get() = session != null

    suspend fun embedText(text: String): FloatArray = embedTexts(listOf(text)).single()

    suspend fun embedTexts(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val s = session()
        val len = tokenizer.maxLength
        val out = ArrayList<FloatArray>(texts.size)
        for (chunk in texts.chunked(batchSize)) {
            val ids = LongArray(chunk.size * len)
            chunk.forEachIndexed { i, t ->
                val e = tokenizer.encode(t)
                check(e.ids.size == len) { "tokenizer length" }
                e.ids.copyInto(ids, i * len)
            }
            val res = s.run(ids, chunk.size, len)
            check(res.size == chunk.size * DIM) { "unexpected model output" }
            for (i in chunk.indices) out += l2(res.copyOfRange(i * DIM, (i + 1) * DIM))
        }
        out
    }

    private suspend fun session(): TextSession = lock.withLock {
        session ?: sessionFactory(modelFile.path).also { session = it }
    }

    /** Drop the session and the tokenizer vocabulary; a later embed reloads both. */
    override fun close() {
        session?.close(); session = null
        (tokenizer as? SigLipTokenizer)?.release()
    }

    companion object {
        const val DIM = 768
        const val OUTPUT_NAME = "pooler_output"
        const val BATCH = 1
        const val MODEL_URL = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/text_model_quantized.onnx"
        const val MODEL_SHA256 = "3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400"
        const val MODEL_BYTES = 283_438_275L

        /** Everything on the text side that decides the vector: model variant, pinned commit, tokenisation, output, normalisation, width. */
        fun fingerprint(variant: String, maxLength: Int = SigLipTokenizer.DEFAULT_MAX_LENGTH) = "siglip2-b16-224@ba1f3b0|$variant|lower|eos|pad$maxLength|pooler|l2|d=$DIM"

        internal fun l2(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat()
            return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
        }
    }
}
