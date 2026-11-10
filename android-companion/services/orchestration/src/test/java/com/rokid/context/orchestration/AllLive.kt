package com.rokid.context.orchestration

import com.rokid.context.model.GonePhotos
import com.rokid.context.model.PhotoLiveness

/** Test liveness: every photo is live and the upload slot is free. [checked] records every re-check the router made. */
object AllLive : PhotoLiveness {
    val checked = java.util.Collections.synchronizedList(mutableListOf<Set<String>>())
    override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = block()
    override suspend fun gone(ids: Set<String>): GonePhotos { checked += ids; return GonePhotos(emptySet()) }
    val checkedMemories = java.util.Collections.synchronizedList(mutableListOf<Set<String>>())
    override suspend fun goneMemories(ids: Set<String>): Set<String> { checkedMemories += ids; return emptySet() }
    override suspend fun goneMarks(ids: Set<String>): Set<String> = emptySet()
}
