package com.rokid.context.canvas

import com.rokid.context.memory.canvas.HelperFence
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A [NodeExecutor] whose chunk suspends on an external job (the laptop helper). [CanvasRunner] treats such an executor differently in exactly two ways (WP8): it captures
 * [captureFence] right after the wipe generation and BEFORE the frames are loaded (the executor never reads a fence of its own for a job), and it runs the chunk in its own
 * coroutine so that [CanvasRunner.cancel] / [CanvasRunner.cancelGroup] interrupt the suspended chunk (the job port ends the helper job when its caller is cancelled).
 */
interface HelperExecutor : NodeExecutor {
    suspend fun captureFence(): HelperFence
}

/** The fence the runner captured before it loaded the frames, handed to the chunk coroutine (a coroutine-context element: `RunContext` lives in `NodeExecutor.kt`, which WP8 does not edit). */
class RunHelperFence(val fence: HelperFence) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunHelperFence>
}

/** Moment runs: for each Moment item key, the frame ids behind it. The executor files them with the job (so a Forget of any member finds it); they are never sent to the helper. */
class RunMomentFrames(val frames: Map<String, List<String>>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunMomentFrames>
}
