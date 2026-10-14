package com.rokid.context.ai.local

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
)

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
    )

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }
}
