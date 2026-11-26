package com.rokid.context.vision.local

/** Thin wrapper over libvision_local.so (llama.cpp + mtmd). Not thread safe: [SmolVlmCaptioner] calls it from one worker at a time. The library loads on the first use of this class, never at app start. */
class NativeVlm : CaptionEngine {
    private companion object { init { System.loadLibrary("vision_local") } }

    private external fun nativeLoad(model: String, mmproj: String, threads: Int): String
    private external fun nativeRun(rgb: ByteArray, w: Int, h: Int, prompt: String, maxTokens: Int): ByteArray?
    private external fun nativeLastError(): String
    private external fun nativeRelease()

    override fun load(files: CaptionModelFiles, threads: Int): String? = nativeLoad(files.model.path, files.mmproj.path, threads).ifEmpty { null }

    override fun run(frame: RgbFrame, prompt: String, maxTokens: Int): String {
        val bytes = nativeRun(frame.rgb, frame.width, frame.height, CaptionConfig.MEDIA_MARKER + prompt, maxTokens) ?: error(nativeLastError())
        return String(bytes, Charsets.UTF_8)
    }

    override fun release() = nativeRelease()
}
