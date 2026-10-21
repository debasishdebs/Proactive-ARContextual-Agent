package com.rokid.context.canvas

import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import kotlinx.serialization.json.JsonElement

/** What flows along a canvas edge. */
sealed interface CanvasValue {
    data object Photo : CanvasValue
    data class Extraction(val facts: List<Fact>, val captions: List<FrameCaption>, val trusted: Boolean? = null, val note: String? = null) : CanvasValue
    data class Json(val value: JsonElement) : CanvasValue
    data class Preview(val rows: List<PreviewRow>) : CanvasValue
}

/** One would-be memory of a dry run. [kind] is "fact" or "caption". */
data class PreviewRow(val kind: String, val text: String, val type: String, val tier: String, val review: String)
