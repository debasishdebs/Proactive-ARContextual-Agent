package com.rokid.context.phone.ui.settings.ai

// INTEGRATION SEAM (Stream D): temporary stand-ins for Stream D's types, so Stream U builds before D merges.
// At integration DELETE THIS FILE and import the real ones instead:
//   com.rokid.context.ai.local.LocalModel, com.rokid.context.ai.local.LocalModels (spec section 3),
//   LocalModelState (D2, next to LocalModelStore in :services:orchestration).
// The shapes below copy spec section 3 and plan D2 exactly, so only import lines change.

/** Stand-in for D1's `com.rokid.context.ai.local.LocalModel` (spec section 3). */
data class LocalModel(
    val id: String, val title: String, val file: String, val url: String, val sha256: String, val bytes: Long,
    val languages: String, val dims: Int, val ramEstimateMb: Int, val license: String, val note: String? = null,
)

/** Stand-in for D1's `com.rokid.context.ai.local.LocalModels`: the single day-one entry, verbatim from spec section 3. */
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
            license = "MIT",
        ),
    )

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }
}

/** Stand-in for D2's `LocalModelState` (plan Task D2, "Interfaces (Produces)"). [Failed.message] is user-safe text. */
sealed interface LocalModelState {
    data object Absent : LocalModelState
    data class Downloading(val fraction: Float?) : LocalModelState
    data object Ready : LocalModelState
    data class Failed(val message: String) : LocalModelState
}
