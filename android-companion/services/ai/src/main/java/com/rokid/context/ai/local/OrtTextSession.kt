package com.rokid.context.ai.local

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/** One text inference session: [n] rows of [seqLen] int64 token ids (row-major) in, n x width floats out. */
interface TextSession : AutoCloseable {
    fun run(ids: LongArray, n: Int, seqLen: Int): FloatArray
}

/**
 * Generic ONNX Runtime session for a text tower (SigLIP 2 text model): int64 `input_ids` [n, seqLen] in, one named 2-D output [n, width] out.
 * A separate class from [OrtImageSession] on purpose: the Nomic image path is untouched.
 *
 * The output is picked BY NAME and a missing name fails at construction (never a silent fallback to output 0, which for SigLIP is `last_hidden_state`).
 * The pinned export's only input is `input_ids` (no attention_mask). If a different export declares an `attention_mask` input it is fed all ones.
 */
internal class OrtTextSession(
    modelPath: String,
    private val outputName: String,
    private val width: Int,
    private val idsName: String = "input_ids",
) : TextSession {
    private val env = OrtEnvironment.getEnvironment()
    private val ort: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())

    init {
        val problem = when {
            idsName !in ort.inputNames -> "model has no input named $idsName"
            outputName !in ort.outputNames -> "model has no output named $outputName"
            else -> null
        }
        if (problem != null) { ort.close(); throw IllegalArgumentException(problem) }
    }

    override fun run(ids: LongArray, n: Int, seqLen: Int): FloatArray {
        require(n >= 1 && ids.size == n * seqLen) { "ids size" }
        val shape = longArrayOf(n.toLong(), seqLen.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { input ->
            val extra = if ("attention_mask" in ort.inputNames) OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape) else null
            try {
                val feed = HashMap<String, OnnxTensor>().apply { put(idsName, input); if (extra != null) put("attention_mask", extra) }
                ort.run(feed, setOf(outputName)).use { r ->
                    val t = r.get(outputName).get() as OnnxTensor
                    return OrtImageSession.rows(t.floatBuffer, t.info.shape, n, width)
                }
            } finally { extra?.close() }
        }
    }

    override fun close() = ort.close()
}
