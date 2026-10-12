package com.rokid.context.model.provider

import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native on-device provider contract for llama.cpp-backed inference.
 *
 * This class is production-oriented in API shape, but requires the JNI bridge
 * (native libs + model loading implementation) to be packaged in app build.
 */
class LlamaCppNativeProvider(
    private val modelPath: String,
) : ModelProvider {
    override suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        require(isNativeAvailable()) {
            "llama.cpp native runtime not available in current APK build"
        }
        require(modelPath.isNotBlank()) { "Local model path is required for native inference" }

        val handle = nativeLoadModel(modelPath)
        try {
            nativeGenerate(handle, prompt)
        } finally {
            nativeReleaseModel(handle)
        }
    }

    companion object {
        private var nativeLoaded = false

        init {
            nativeLoaded = try {
                System.loadLibrary("llama_jni")
                true
            } catch (_: Throwable) {
                false
            }
        }

        fun isNativeAvailable(): Boolean = nativeLoaded

        @JvmStatic
        private external fun nativeLoadModel(modelPath: String): Long

        @JvmStatic
        private external fun nativeGenerate(handle: Long, prompt: String): String

        @JvmStatic
        private external fun nativeReleaseModel(handle: Long)
    }
}
