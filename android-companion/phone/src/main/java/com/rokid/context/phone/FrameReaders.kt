package com.rokid.context.phone

import android.content.Context
import com.rokid.context.core.ui.components.FrameDecode
import com.rokid.context.core.ui.components.FrameOpener
import com.rokid.context.memory.frames.FrameImages

/** Gaps G1: one reader for every frame image on the phone ([FrameImages.open]: plain or sealed). Called at app start. */
object FrameReaders {
    fun install(context: Context) {
        FrameImages.install(context)
        FrameDecode.opener = FrameOpener { path -> FrameImages.open(path) }
    }
}
