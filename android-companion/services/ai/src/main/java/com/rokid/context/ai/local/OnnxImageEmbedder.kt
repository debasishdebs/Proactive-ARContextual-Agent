package com.rokid.context.ai.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.rokid.context.core.log.PcLog
import com.rokid.context.ai.ProviderConfigException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** One inference session: a batch of [n] preprocessed images (n x 3 x 224 x 224) in, n x 768 CLS vectors out. */
interface ImageSession : AutoCloseable {
    fun run(batch: FloatArray, n: Int): FloatArray
}

/**
 * Nomic vision v1.5 over ONNX Runtime. CLS (768) -> L2 -> first [dims] -> L2. Batches of 8 on Dispatchers.Default.
 * Session creation is retried once; a second failure marks image embedding unavailable for the process.
 */
class OnnxImageEmbedder(
    private val modelPath: String,
    private val dims: Int,
    private val cfg: NomicVision.PreprocessConfig = NomicVision.PreprocessConfig.PINNED,
    private val sessionFactory: (String) -> ImageSession = ::OrtImageSession,
    private val preprocess: (ByteArray, NomicVision.PreprocessConfig) -> FloatArray = NomicVision::preprocess,
) : AutoCloseable {
    init { require(dims in 1..NomicVision.CLS_DIMS) { "dims" } }

    private val lock = Mutex()
    private var session: ImageSession? = null

    suspend fun embed(jpegs: List<ByteArray>): List<FloatArray> = rawCls(jpegs).map { shorten(l2(it), dims) }

    /** The raw 768 CLS vectors, before any transform (device parity test). */
    suspend fun rawCls(jpegs: List<ByteArray>): List<FloatArray> = withContext(Dispatchers.Default) {
        if (jpegs.isEmpty()) return@withContext emptyList()
        val s = session()
        val out = ArrayList<FloatArray>(jpegs.size)
        for (chunk in jpegs.chunked(BATCH)) {
            val per = 3 * NomicVision.INPUT * NomicVision.INPUT
            val batch = FloatArray(chunk.size * per)
            chunk.forEachIndexed { i, j -> preprocess(j, cfg).copyInto(batch, i * per) }
            val res = s.run(batch, chunk.size)
            check(res.size == chunk.size * NomicVision.CLS_DIMS) { "unexpected model output" }
            for (i in chunk.indices) out += res.copyOfRange(i * NomicVision.CLS_DIMS, (i + 1) * NomicVision.CLS_DIMS)
        }
        out
    }

    private suspend fun session(): ImageSession = lock.withLock {
        session?.let { return it }
        if (unavailable) throw ProviderConfigException(UNAVAILABLE)
        for (attempt in 1..2) {
            try {
                return sessionFactory(modelPath).also { session = it }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Native-load Errors (UnsatisfiedLinkError, NoClassDefFoundError, OOM...) degrade to text-only like any failure; class name only.
                PcLog.w(TAG, "session create failed attempt=$attempt class=${e.javaClass.simpleName}")
            }
        }
        unavailable = true
        throw ProviderConfigException(UNAVAILABLE)
    }

    override fun close() { session?.close(); session = null }

    companion object {
        const val BATCH = 8
        private const val TAG = "OnnxImageEmbedder"
        private const val UNAVAILABLE = "On-device image embedding is unavailable on this device"
        @Volatile private var unavailable = false
        val isUnavailable: Boolean get() = unavailable
        internal fun resetUnavailableForTest() { unavailable = false }

        internal fun l2(v: FloatArray): FloatArray {
            var s = 0.0
            for (x in v) s += x.toDouble() * x
            val n = sqrt(s).toFloat()
            return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
        }

        internal fun shorten(v: FloatArray, dims: Int): FloatArray = l2(v.copyOf(dims))
    }
}

/**
 * The real ONNX Runtime session. Accepts either [n,width] or [n,tokens,width] output (3-D: CLS = token 0).
 *
 * [outputName] picks the model output BY NAME; null (the default) = output 0, exactly the behaviour before SigLIP, so the Nomic path is unchanged. SigLIP 2's vision
 * ONNX has outputs `last_hidden_state` (0, [n,196,768], NOT an embedding) then `pooler_output` (1, [n,768], the MAP-head embedding): it must be asked for by name.
 * A name the model does not have fails at construction (never a silent fallback to output 0).
 */
internal class OrtImageSession(
    modelPath: String,
    private val outputName: String? = null,
    private val width: Int = NomicVision.CLS_DIMS,
) : ImageSession {
    private val env = OrtEnvironment.getEnvironment()
    private val ort: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = ort.inputNames.first()

    init {
        if (outputName != null && outputName !in ort.outputNames) {
            ort.close()
            throw IllegalArgumentException("model has no output named $outputName")
        }
    }

    override fun run(batch: FloatArray, n: Int): FloatArray {
        val shape = longArrayOf(n.toLong(), 3, NomicVision.INPUT.toLong(), NomicVision.INPUT.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(batch), shape).use { input ->
            val feed = mapOf(inputName to input)
            (if (outputName == null) ort.run(feed) else ort.run(feed, setOf(outputName))).use { r ->
                val t = (if (outputName == null) r[0] else r.get(outputName).get()) as OnnxTensor
                val buf = t.floatBuffer
                val flat = FloatArray(buf.remaining())
                buf.get(flat)
                return rows(flat, t.info.shape, n, width)
            }
        }
    }

    override fun close() = ort.close()

    companion object {
        /**
         * First [width] values of each of the [n] rows: [flat] is [n,width] (2-D) or [n,tokens,width] (3-D, row = token 0). Pure, so the Nomic reading
         * (CLS from output 0) is unit-tested without ORT.
         */
        internal fun rows(flat: FloatArray, shape: LongArray, n: Int, width: Int): FloatArray {
            val stride = if (shape.size == 3) (shape[1] * shape[2]).toInt() else width
            require(n >= 1 && flat.size >= (n - 1) * stride + width) { "unexpected model output" }
            val out = FloatArray(n * width)
            for (i in 0 until n) System.arraycopy(flat, i * stride, out, i * width, width)
            return out
        }
    }
}
