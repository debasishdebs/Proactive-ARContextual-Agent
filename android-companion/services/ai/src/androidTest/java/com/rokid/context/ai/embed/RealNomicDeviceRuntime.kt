package com.rokid.context.ai.embed

import com.rokid.context.ai.local.NomicBundle
import com.rokid.context.ai.local.NomicVision
import com.rokid.context.model.provider.LlamaCppNativeProvider
import kotlinx.coroutines.runBlocking
import java.io.File

/** The real on-device Nomic runtime for [NomicReferenceDeviceTest]: GGUF text through llama.cpp, vision through ORT, both with raw (untransformed) outputs. */
class RealNomicDeviceRuntime(private val models: File) : NomicReferenceDeviceTest.NomicDeviceRuntime {
    override fun rawText(prefixed: String): FloatArray =
        LlamaCppNativeProvider.embed(File(models, TEXT_FILE).path, listOf(prefixed), normalize = false).single()

    override fun rawImage(jpeg: ByteArray): FloatArray = runBlocking {
        NomicBundle.imageEmbedder(File(models, NomicBundle.VISION_FILE).path, NomicVision.STORED_DIMS).rawCls(listOf(jpeg)).single()
    }

    companion object { const val TEXT_FILE = "nomic-embed-text-v1.5.Q8_0.gguf" }
}
