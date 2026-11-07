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

/**
 * Codex r9 P1-1: a coroutine-context element the caller installs around work that sends photos. The router runs every provider call that carries images
 * through [send], INSIDE that call's permit and right before the bytes leave: the guard re-checks that the photos are still live and holds whatever keeps
 * them live (the DataOpGate's dispatch side) for the whole call, or throws without calling [call] (nothing is uploaded). Absent = no check.
 */
interface DispatchGuard : kotlin.coroutines.CoroutineContext.Element {
    suspend fun <T> send(call: suspend () -> T): T
    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
    companion object Key : kotlin.coroutines.CoroutineContext.Key<DispatchGuard>
}
