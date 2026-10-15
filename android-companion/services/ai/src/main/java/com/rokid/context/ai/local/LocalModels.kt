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
    )

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }
}
