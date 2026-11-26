package com.rokid.context.ai.local

/** One file of a [LocalModel] bundle. [url] is pinned to one repo commit. */
data class ModelFile(val file: String, val url: String, val sha256: String, val bytes: Long)

/** A downloadable on-device model. [url] is pinned to one repo commit; [bytes] and [sha256] are verified after download. */
data class LocalModel(
    val id: String,
    val title: String,
    val file: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val languages: String,
    val dims: Int,
    val ramEstimateMb: Int,
    val license: String,
    val note: String? = null,
    /** Every file of the bundle; defaults to the single file described by [file]/[url]/[sha256]/[bytes] (the primary file, the one a llama.cpp endpoint loads). */
    val files: List<ModelFile> = listOf(ModelFile(file, url, sha256, bytes)),
    /** False for a model that is downloaded here but never bound as the Embeddings endpoint (SigLIP 2: ONNX towers read by the shadow embedder only); the page then offers Download and Delete, no "Use for Embeddings". */
    val bindable: Boolean = true,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

/** The fixed catalog (spec section 3). Users never supply URLs; adding a model is one entry here. */
object LocalModels {
    val all: List<LocalModel> = listOf(
        LocalModel(
            id = "bge-small-en-v1.5-q8",
            title = "bge-small (English)",
            file = "bge-small-en-v1.5-q8_0.gguf",
            url = "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf/resolve/d32f8c040ea3b516330eeb75b72bcc2d3a780ab7/bge-small-en-v1.5-q8_0.gguf",
            sha256 = "ec38e8da142596baa913124ae50550de284b6916bf59577ef2f0cb9660c2f514",
            bytes = 36_806_944L,
            languages = "English",
            dims = 384,
            ramEstimateMb = 90,
            license = "MIT (upstream BAAI MIT)",
        ),
        // Catalogued and recommended, but never auto-bound: bge-small stays the default until the user picks it (spec F.1/F.5).
        // Primary file = the text GGUF; the vision ONNX (quantized, present at the pinned commit) rides along in [files].
        LocalModel(
            id = "nomic-embed-v1.5",
            title = "Nomic Embed v1.5 (text + images)",
            file = "nomic-embed-text-v1.5.Q8_0.gguf",
            url = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/0188c9bf409793f810680a5a431e7b899c46104c/nomic-embed-text-v1.5.Q8_0.gguf",
            sha256 = "3e24342164b3d94991ba9692fdc0dd08e3fd7362e0aacc396a9a5c54a544c3b7",
            bytes = 146_146_432L,
            languages = "English",
            dims = 768,
            ramEstimateMb = 350,
            license = "Apache-2.0",
            note = "Recommended",
            files = listOf(
                ModelFile(
                    file = "nomic-embed-text-v1.5.Q8_0.gguf",
                    url = "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/0188c9bf409793f810680a5a431e7b899c46104c/nomic-embed-text-v1.5.Q8_0.gguf",
                    sha256 = "3e24342164b3d94991ba9692fdc0dd08e3fd7362e0aacc396a9a5c54a544c3b7",
                    bytes = 146_146_432L,
                ),
                ModelFile(
                    file = "nomic-embed-vision-v1.5.model_quantized.onnx",
                    url = "https://huggingface.co/nomic-ai/nomic-embed-vision-v1.5/resolve/e3a725bce72db07ca4adb1d83da08903f3ee02f8/onnx/model_quantized.onnx",
                    sha256 = "ba9107df6e412828dae8c675096209aa39f6536de8ec8d9a872665b54dc750c3",
                    bytes = 96_745_606L,
                ),
            ),
        ),
        // SigLIP 2 (1.4.4 shadow embedder): opt-in only, never the default and never bound as Embeddings ([LocalModel.bindable] = false). The three file names are the ones SigLipBundle opens; url/sha256/bytes are the
        // values recorded in SigLipImageEmbedder / SigLipTextEmbedder / SigLipTokenizer. The tokenizer is pinned to the google repo commit its sha256 was recorded against (same 4,241,003 bytes as the ONNX export).
        LocalModel(
            id = "siglip2-base-p16-224",
            title = "SigLIP 2 base (photo matching, experimental)",
            file = "siglip2-base-p16-224.vision_model_quantized.onnx",
            url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/vision_model_quantized.onnx",
            sha256 = "5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070",
            bytes = 94_553_333L,
            languages = "Images + text",
            dims = 768,
            ramEstimateMb = 380,
            license = "Apache-2.0",
            note = "Developer shadow only",
            files = listOf(
                ModelFile(
                    file = "siglip2-base-p16-224.vision_model_quantized.onnx",
                    url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/vision_model_quantized.onnx",
                    sha256 = "5f2b401c1a4fc095702a5d45348e17ad46c4f87064085365b43c6e8eaa5c0070",
                    bytes = 94_553_333L,
                ),
                ModelFile(
                    file = "siglip2-base-p16-224.text_model_quantized.onnx",
                    url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/text_model_quantized.onnx",
                    sha256 = "3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400",
                    bytes = 283_438_275L,
                ),
                ModelFile(
                    file = "siglip2-base-p16-224.tokenizer.model",
                    url = "https://huggingface.co/google/siglip2-base-patch16-224/resolve/75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2/tokenizer.model",
                    sha256 = "61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2",
                    bytes = 4_241_003L,
                ),
            ),
            bindable = false,
        ),
        // Offline photo captioner (V1, 2026-11-26): SmolVLM2-500M Q8_0 + its vision projector, read by :services:vision-local through llama.cpp mtmd only behind the `offlineCaption` Developer flag (default OFF); never bound as
        // Embeddings ([LocalModel.bindable] = false). Files are pinned to the ggml-org repo commit below; sizes and sha256 were checked against the repo (x-linked-size / x-linked-etag) and the spike's copies.
        LocalModel(
            id = "smolvlm2-500m-q8",
            title = "SmolVLM2 500M (offline photo captions, experimental)",
            file = "SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
            url = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/ccd7aae53bcb1997355c2f094959e72b3642ce17/SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
            sha256 = "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc",
            bytes = 436_808_704L,
            languages = "Images + text",
            dims = 0,
            ramEstimateMb = 900,
            license = "Apache-2.0",
            note = "Developer only",
            files = listOf(
                ModelFile(
                    file = "SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
                    url = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/ccd7aae53bcb1997355c2f094959e72b3642ce17/SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
                    sha256 = "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc",
                    bytes = 436_808_704L,
                ),
                ModelFile(
                    file = "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
                    url = "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/ccd7aae53bcb1997355c2f094959e72b3642ce17/mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf",
                    sha256 = "921dc7e259f308e5b027111fa185efcbf33db13f6e35749ddf7f5cdb60ef520b",
                    bytes = 108_785_184L,
                ),
            ),
            bindable = false,
        ),
    )

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }
}
