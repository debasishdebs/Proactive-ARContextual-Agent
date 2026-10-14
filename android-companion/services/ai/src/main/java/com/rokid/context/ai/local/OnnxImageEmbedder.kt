package com.rokid.context.ai.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
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
            } catch (e: Exception) {
                Log.w(TAG, "session create failed attempt=$attempt")
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

/** The real ONNX Runtime session. Accepts either [n,768] or [n,tokens,768] output (CLS = token 0). */
internal class OrtImageSession(modelPath: String) : ImageSession {
    private val env = OrtEnvironment.getEnvironment()
    private val ort: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val inputName = ort.inputNames.first()

    override fun run(batch: FloatArray, n: Int): FloatArray {
        val shape = longArrayOf(n.toLong(), 3, NomicVision.INPUT.toLong(), NomicVision.INPUT.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(batch), shape).use { input ->
            ort.run(mapOf(inputName to input)).use { r ->
                val t = r[0] as OnnxTensor
                val buf = t.floatBuffer
                val dims = t.info.shape
                val d = NomicVision.CLS_DIMS
                val out = FloatArray(n * d)
                val stride = if (dims.size == 3) (dims[1] * dims[2]).toInt() else d
                for (i in 0 until n) { buf.position(i * stride); buf.get(out, i * d, d) }
                return out
            }
        }
    }

    override fun close() = ort.close()
}
