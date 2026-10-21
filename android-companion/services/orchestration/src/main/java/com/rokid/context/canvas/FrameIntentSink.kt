package com.rokid.context.canvas

/**
 * Where the canvas hands a photo's intent label (spec section 5, S9). The default does nothing: wave B owns the `frame_meta` intent
 * columns and their writer (`IntentSampler` through `RoomIntentFrameStore.setIntent`) and binds a real sink when it lands.
 * Neither this interface nor the interpreter writes `frame_meta`; the caller (`LiveExtract.commit`, Task 6) calls [record] only
 * after the save, fenced, for saved frames.
 */
fun interface FrameIntentSink {
    suspend fun record(frameId: String, label: String, confidence: Float?, atMs: Long)

    companion object {
        val None = FrameIntentSink { _, _, _, _ -> }
    }
}
