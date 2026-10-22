package com.rokid.context.phone.canvas.api

import com.rokid.context.canvas.AskDebugPhotos

/**
 * The one place the Ask photo gate of a canvas replay is bound. Until something calls [bind] it is [AskDebugPhotos.Refuse]: a replay sends no photo,
 * the debug view loads no image, and no photo scan runs. The wave 3 merge binds the real gate here, exactly the one a live Ask passes:
 * `allowed` = `AnswerService.photosAllowedNow(context)` (the user's "Send matching photos with questions" switch, default OFF, not paused,
 * not in quiet hours, an ANSWER endpoint that can see images), `recall` = the photo search with `AskPhotoPolicy.select` and the photo lines of
 * `AnswerService.retrieve`, and the lazy `images` = `FramePhotos.load`. Nothing else may bypass it.
 */
object AskPhotoBinding {
    @Volatile private var bound: AskDebugPhotos = AskDebugPhotos.Refuse

    fun current(): AskDebugPhotos = bound

    fun bind(photos: AskDebugPhotos) { bound = photos }

    /** Back to refusing every photo (tests). */
    fun reset() { bound = AskDebugPhotos.Refuse }
}
