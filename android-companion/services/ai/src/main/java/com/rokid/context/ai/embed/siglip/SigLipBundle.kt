package com.rokid.context.ai.embed.siglip

import java.io.File

/**
 * Where the SigLIP 2 files live on the phone and how a shadow-only caller opens them (1.4.4). The files are NOT downloaded by any code: there is no catalog entry, no download worker and no
 * network call. They are sideloaded by hand into the app's `files/models/` folder (the same folder the Local models page uses), under the names below:
 *   [VISION_FILE]    = vision_model_quantized.onnx     (94,553,333 B, sha256 [SigLipImageEmbedder.MODEL_SHA256])
 *   [TEXT_FILE]      = text_model_quantized.onnx       (283,438,275 B, sha256 [SigLipTextEmbedder.MODEL_SHA256]; optional, without it only the image channel scores)
 *   [TOKENIZER_FILE] = tokenizer.model                 (4,241,003 B; needed with the text file)
 * Opening checks that a file exists and has the exact size; the sha256 is checked by the person who sideloads (and by the parity tests). All inference is local ONNX Runtime: nothing here can reach a network,
 * so it is the same under "Offline only" as with a connection.
 */
object SigLipBundle {
    const val VISION_FILE = "siglip2-base-p16-224.vision_model_quantized.onnx"
    const val TEXT_FILE = "siglip2-base-p16-224.text_model_quantized.onnx"
    const val TOKENIZER_FILE = "siglip2-base-p16-224.tokenizer.model"
    const val TOKENIZER_BYTES = 4_241_003L

    /** Which of the three files are present with the right size. */
    class Presence(val vision: Boolean, val text: Boolean, val tokenizer: Boolean) {
        val usable: Boolean get() = vision
        val textUsable: Boolean get() = text && tokenizer
    }

    fun presence(modelsDir: File): Presence = Presence(
        vision = sized(File(modelsDir, VISION_FILE), SigLipImageEmbedder.MODEL_BYTES),
        text = sized(File(modelsDir, TEXT_FILE), SigLipTextEmbedder.MODEL_BYTES),
        tokenizer = sized(File(modelsDir, TOKENIZER_FILE), TOKENIZER_BYTES),
    )

    /**
     * The image-text embedder over what is present, or null when the vision file is missing (no SigLIP shadow). With the vision file only, [SigLipImageTextEmbedder.embedText] fails and the shadow
     * logs the pack-place text channel as missing, which is the plan's "vision first" step. Towers load lazily on first use.
     */
    fun open(modelsDir: File): SigLipImageTextEmbedder? {
        val p = presence(modelsDir)
        if (!p.usable) return null
        val vision = SigLipImageEmbedder(File(modelsDir, VISION_FILE))
        // Lazy: a missing text file or tokenizer is only touched by the first embedText, which then throws and the shadow treats it as "no text channel".
        val text = SigLipTextEmbedder(File(modelsDir, TEXT_FILE), SigLipTokenizer(File(modelsDir, TOKENIZER_FILE)))
        return SigLipImageTextEmbedder(vision, text)
    }

    private fun sized(f: File, bytes: Long) = f.isFile && f.length() == bytes
}
