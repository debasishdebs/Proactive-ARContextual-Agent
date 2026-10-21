package com.rokid.context.canvas

import com.rokid.context.orchestration.ExtractPhoto

/** Loads the photos a run is over. The production adapter wraps FramePhotos.load: gone and unseen frames are [PhotoLoad.missing]. */
fun interface PhotoSource {
    suspend fun load(frameIds: List<String>): PhotoLoad
}

data class PhotoLoad(val photos: List<ExtractPhoto>, val missing: Set<String>)
