package com.rokid.context.vision.local

import java.io.File

/** The two model files of the bundle. */
data class CaptionModelFiles(val model: File, val mmproj: File)

/** An RGB image, w*h*3 bytes (RGBRGB...). */
class RgbFrame(val rgb: ByteArray, val width: Int, val height: Int)

/** The native model, behind an interface so the captioner is tested with a fake. Not thread safe: [SmolVlmCaptioner] serialises every call. */
interface CaptionEngine {
    /** Loads the model and the vision projector. Null on success, else a fixed error phrase. */
    fun load(files: CaptionModelFiles, threads: Int): String?

    /** Captions one image. Returns the generated text (may be empty); throws on a native failure. */
    fun run(frame: RgbFrame, prompt: String, maxTokens: Int): String

    /** Frees the model and every native buffer. Safe to call when nothing is loaded. */
    fun release()
}

/** Decodes a JPEG to the size the model wants. Null when the file cannot be decoded. */
fun interface FrameLoader { fun load(jpegPath: String, maxSidePx: Int): RgbFrame? }
