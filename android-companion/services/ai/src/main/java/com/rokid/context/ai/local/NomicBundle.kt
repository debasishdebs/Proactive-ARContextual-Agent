package com.rokid.context.ai.local

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The Nomic Embed v1.5 bundle as ONE embedding space: text (GGUF, 256) and images (ONNX, [NomicVision.STORED_DIMS]). */
object NomicBundle {
    const val VISION_FILE = "nomic-embed-vision-v1.5.model_quantized.onnx"

    /** One space for text (256) and images (imageDims): "nomic-embed-text-v1.5|mean|ln|d=256|l2+nomic-embed-vision-v1.5|cls|l2|d=<imageDims>|l2". */
    fun fingerprint(imageDims: Int = NomicVision.STORED_DIMS): String =
        NomicText.fingerprint(NomicText.TEXT_DIMS) + "+" + NomicVision.fingerprint(imageDims)

    /** The vision model sits beside the text GGUF in the models directory. */
    fun visionPath(textModelPath: String): File = File(File(textModelPath).parentFile, VISION_FILE)

    private val embedders = ConcurrentHashMap<Pair<String, Int>, OnnxImageEmbedder>()

    /** The process-wide image embedder for a vision file and width (one ONNX session each, created lazily on first use). */
    fun imageEmbedder(visionPath: String, dims: Int): OnnxImageEmbedder = embedders.getOrPut(visionPath to dims) { OnnxImageEmbedder(visionPath, dims) }
}
