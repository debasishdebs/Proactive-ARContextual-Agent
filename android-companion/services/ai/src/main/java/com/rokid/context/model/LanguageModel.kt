package com.rokid.context.model

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply

/** Who a model call is for; the call budget (P2) gives each class its own token rules. */
/** [EXPERIMENT]: experiment-canvas runs. Counted for diagnostics but never charged to the live budget (the run's own caps limit it). */
enum class CallClass { USER, EXTRACT, NUDGE, CAPTION_QUEUE, BACKFILL, OTHER, EXPERIMENT }

/**
 * Routing-aware seam over the router (spec F.1): the router's public API plus an explicit [CallClass]. Role routing,
 * endpoint-specific request building, structured formats, fallbacks and [RoutedReply] metadata all stay inside ModelRouter.
 */
interface LanguageModel {
    suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply
    suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply
    suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply
}

/** Wraps each individual provider call (fallbacks and retries included) with the caller's class. A regular interface: Kotlin forbids type parameters on a fun interface's method. */
interface CallGate {
    suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T
}

object PassThroughGate : CallGate {
    override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T = block()
}

/** Of the ids asked about, those no longer live; [wiped] = Delete all ran since the caller loaded them (then every id counts as gone). */
data class GonePhotos(val ids: Set<String>, val wiped: Boolean = false) {
    val none: Boolean get() = ids.isEmpty() && !wiped
}

/**
 * The image-dispatch guard's caller half (codex r10). Every request that carries a photo carries one of these, and [com.rokid.context.ai.ModelRouter] (the
 * one choke point every model call goes through) runs each provider call as: call permit, then [holdUploadSlot] (the slot a Forget, Delete all or
 * retention delete waits for), then [gone] over every attached photo id, then the network write, then release. A photo found gone drops the call unsent
 * with [com.rokid.context.ai.PhotoForgottenException], never retried as text or on another endpoint. The one production implementation is
 * `com.rokid.context.work.frames.FrameLiveness`.
 */
interface PhotoLiveness {
    suspend fun <T> holdUploadSlot(block: suspend () -> T): T
    suspend fun gone(ids: Set<String>): GonePhotos
    /** Codex r12: of these source memory ids ([PayloadSources.memoryIds]), those forgotten (gone or INVALIDATED). Empty = all live. */
    suspend fun goneMemories(ids: Set<String>): Set<String>
    /** Codex r15: of these mark label ids ([PayloadSources.markIds]), those gone (forgotten, undone or wiped). Empty = all live. */
    suspend fun goneMarks(ids: Set<String>): Set<String>
}
