package com.rokid.context.phone.vision

import android.content.Context
import com.rokid.context.ai.local.LocalModels
import com.rokid.context.core.model.vision.OfflineCaptioner
import com.rokid.context.data.models.LocalModelStore
import com.rokid.context.orchestration.forget.DeveloperFlags
import com.rokid.context.vision.local.AndroidDeviceState
import com.rokid.context.vision.local.BitmapFrameLoader
import com.rokid.context.vision.local.CaptionEngine
import com.rokid.context.vision.local.CaptionModelFiles
import com.rokid.context.vision.local.NativeVlm
import com.rokid.context.vision.local.RgbFrame
import com.rokid.context.vision.local.SmolVlmCaptioner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * The one offline captioner of the app (V1). Nothing is created at app start: the captioner appears on first use (the Developer test), and the native library
 * loads only when the first caption runs. [OfflineCaptioner.caption] refuses unless the `offlineCaption` Developer flag is ON (default OFF).
 */
object OfflineCaptionerHolder {
    const val MODEL_ID = "smolvlm2-500m-q8"

    private val dispatcher by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "offline-caption").apply { isDaemon = true } }.asCoroutineDispatcher() }
    @Volatile private var instance: SmolVlmCaptioner? = null

    fun get(context: Context): OfflineCaptioner = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    /** Frees the model now if a captioner exists (the flag was switched off, or a test ended). */
    fun releaseIfCreated() {
        val c = instance ?: return
        CoroutineScope(SupervisorJob() + dispatcher).launch { c.release() }
    }

    /** Both files of the catalog bundle, present with the exact size; null while the download is missing or partial. */
    fun modelFiles(context: Context): CaptionModelFiles? {
        val m = LocalModels.byId(MODEL_ID) ?: return null
        val dir = LocalModelStore.modelsDir(context)
        val files = m.files.map { it to File(dir, it.file) }
        if (files.any { (spec, f) -> !f.isFile || f.length() != spec.bytes }) return null
        return CaptionModelFiles(files[0].second, files[1].second)
    }

    private fun create(app: Context) = SmolVlmCaptioner(
        engine = LazyEngine(),
        models = { modelFiles(app) },
        enabled = { DeveloperFlags.offlineCaption(app) },
        device = AndroidDeviceState(app),
        frames = BitmapFrameLoader(),
        dispatcher = dispatcher,
    )

    /** Creates the [NativeVlm] (which loads the native library) only when a caption first loads the model; release never loads the library. */
    private class LazyEngine : CaptionEngine {
        private var native: NativeVlm? = null
        private fun vlm() = native ?: NativeVlm().also { native = it }
        override fun load(files: CaptionModelFiles, threads: Int) = vlm().load(files, threads)
        override fun run(frame: RgbFrame, prompt: String, maxTokens: Int) = vlm().run(frame, prompt, maxTokens)
        override fun release() { native?.release() }
    }
}
