package com.rokid.context.ai.embed.siglip

import android.graphics.BitmapFactory
import com.rokid.context.ai.local.ImageSession
import com.rokid.context.ai.local.OrtImageSession
import com.rokid.context.memory.ImageDecodeException
import com.rokid.context.memory.ImageOnlyEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * SigLIP 2 B/16-224 VISION tower on ONNX Runtime (milestone V of plan 2026-11-20-siglip2-integration-spike). Standalone: NOT wired into LocalModels, the catalog, EmbeddingSpec
 * or any production flow. Image in -> `pooler_output` (output 1, the MAP-head embedding; output 0 `last_hidden_state` is NOT an embedding) -> L2 -> 768 floats.
 * The text tower is a later milestone, so this is an [ImageOnlyEmbedder]; the combined `ImageTextEmbedder` comes with the text half.
 *
 * Model file verified in this milestone (NOT committed to git; a human must re-check the hash):
 *   url    https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/vision_model_quantized.onnx
 *   bytes  94,553,333
 *   sha256 5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070  (computed locally after download; equals the LFS oid in the spike)
 *   graph  input `pixel_values` [batch,3,224,224] float32; outputs `last_hidden_state` [b,196,768], `pooler_output` [b,768]
 *
 * Catalog fields a later wiring step needs (do NOT add them yet):
 *   id        siglip2-base-p16-224-vision (a separate id from the later text tower), role = PHOTO_SPACE (never bindable as the text embedder)
 *   url/sha256/size  as above
 *   licence   upstream google/siglip2-base-patch16-224 is apache-2.0; the onnx-community conversion repo declares NO licence field (ruling + NOTICE crediting Google needed)
 *   space id  [FINGERPRINT]; keep it out of `embedding_space` / `EmbeddingSpec` (it must never become `EmbeddingDao.current()`)
 *
 * Failure policy here is simple: session creation errors propagate to the caller (who treats any failure as "no score"); the process-wide "unavailable" latch
 * of OnnxImageEmbedder is a wiring-time decision.
 */
class SigLipImageEmbedder(
    private val modelFile: File,
    private val variant: String = "vision_quantized",
    private val batchSize: Int = BATCH,
    private val sessionFactory: (String) -> ImageSession = { OrtImageSession(it, outputName = OUTPUT_NAME, width = DIM) },
    private val decode: (ByteArray) -> Argb = { decodeBitmap(it) },
) : ImageOnlyEmbedder, AutoCloseable {
    /** Decoded pixels: packed ARGB, w x h. */
    class Argb(val pixels: IntArray, val w: Int, val h: Int)

    override val dim: Int get() = DIM
    override val spaceId: String get() = fingerprint(variant)

    private val lock = Mutex()
    private var session: ImageSession? = null

    override suspend fun embedImage(bytes: ByteArray): FloatArray = embedImages(listOf(bytes)).single()

    suspend fun embedImages(images: List<ByteArray>): List<FloatArray> = withContext(Dispatchers.Default) {
        embedArgb(images.map { decode(it) })
    }

    /** Pixels in (already decoded): preprocess, run in batches, L2. */
    suspend fun embedArgb(images: List<Argb>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (images.isEmpty()) return@withContext emptyList()
        val s = session()
        val per = 3 * SigLipPreprocess.SIDE * SigLipPreprocess.SIDE
        val out = ArrayList<FloatArray>(images.size)
        for (chunk in images.chunked(batchSize)) {
            val batch = FloatArray(chunk.size * per)
            chunk.forEachIndexed { i, a -> SigLipPreprocess.preprocessArgb(a.pixels, a.w, a.h).copyInto(batch, i * per) }
            val res = s.run(batch, chunk.size)
            check(res.size == chunk.size * DIM) { "unexpected model output" }
            for (i in chunk.indices) out += l2(res.copyOfRange(i * DIM, (i + 1) * DIM))
        }
        out
    }

    /** Run on tensors that are already preprocessed (parity test: reference tensors straight into ORT). Returns L2-normalised vectors. */
    suspend fun embedTensors(tensors: List<FloatArray>): List<FloatArray> = withContext(Dispatchers.Default) {
        val s = session()
        val per = 3 * SigLipPreprocess.SIDE * SigLipPreprocess.SIDE
        tensors.chunked(batchSize).flatMap { chunk ->
            val batch = FloatArray(chunk.size * per)
            chunk.forEachIndexed { i, t -> require(t.size == per) { "tensor size" }; t.copyInto(batch, i * per) }
            val res = s.run(batch, chunk.size)
            chunk.indices.map { l2(res.copyOfRange(it * DIM, (it + 1) * DIM)) }
        }
    }

    private suspend fun session(): ImageSession = lock.withLock {
        session ?: sessionFactory(modelFile.path).also { session = it }
    }

    override fun close() { session?.close(); session = null }

    companion object {
        const val DIM = 768
        const val OUTPUT_NAME = "pooler_output"
        /** One image per ORT run by default: the int8 model quantises activations per TENSOR (DynamicQuantizeLinear), so an image's vector depends on its batchmates (cosine 0.975-0.996 vs the same image alone, measured). */
        const val BATCH = 1
        const val MODEL_URL = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/vision_model_quantized.onnx"
        const val MODEL_SHA256 = "5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070"
        const val MODEL_BYTES = 94_553_333L

        /** Everything that decides the vector: model, pinned commit, output, preprocessing, normalisation, width. Vision half only (text half adds its own part later). */
        fun fingerprint(variant: String) = "siglip2-b16-224@ba1f3b0|$variant|pooler|bilinear224|l2|d=$DIM"

        internal fun l2(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat()
            return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
        }

        /** Same decode as NomicVision (EXIF orientation is NOT applied, like PIL's default). */
        internal fun decodeBitmap(jpeg: ByteArray): Argb {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: throw ImageDecodeException("undecodable image")
            try {
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                return Argb(px, bmp.width, bmp.height)
            } finally {
                bmp.recycle()
            }
        }
    }
}
