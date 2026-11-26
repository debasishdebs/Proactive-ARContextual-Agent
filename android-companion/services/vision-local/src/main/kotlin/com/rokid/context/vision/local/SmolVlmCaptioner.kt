package com.rokid.context.vision.local

import com.rokid.context.core.model.vision.CaptionRefusal
import com.rokid.context.core.model.vision.CaptionRefused
import com.rokid.context.core.model.vision.OfflineCaptioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Captions a photo with SmolVLM2-500M (llama.cpp mtmd), fully offline.
 *
 * - OFF unless [enabled] says so (the `offlineCaption` Developer flag, default OFF); a refusal never loads anything.
 * - One inference at a time ([mutex]); runs only on [dispatcher] (a dedicated thread in the app) and refuses on the main thread.
 * - Lazy: the model loads on the first caption and is released [idleReleaseMs] after the last one, or by [release] at the end of a batch, so the phone cools.
 * - Refuses (a [CaptionRefused] result, never a crash) when the phone is thermally SEVERE or worse, in battery saver, or under 20% and not charging.
 *
 * PRIVACY: the caption and the path are content. This class logs nothing; callers log counts and latency only. Nothing is stored and nothing touches the network.
 */
class SmolVlmCaptioner(
    private val engine: CaptionEngine,
    private val models: () -> CaptionModelFiles?,
    private val enabled: () -> Boolean,
    private val device: () -> DeviceState,
    private val frames: FrameLoader,
    private val dispatcher: CoroutineDispatcher,
    private val mainThread: () -> Boolean = ::isMainThread,
    private val threads: Int = CaptionConfig.THREADS,
    private val idleReleaseMs: Long = CaptionConfig.IDLE_RELEASE_MS,
) : OfflineCaptioner {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var idle: Job? = null // guarded by [mutex]
    private var loaded = false // guarded by [mutex]

    override suspend fun caption(jpegPath: String): Result<String> {
        if (!enabled()) return refused(CaptionRefusal.DISABLED)
        return withContext(dispatcher) {
            if (mainThread()) return@withContext refused(CaptionRefusal.MAIN_THREAD)
            mutex.withLock {
                idle?.cancel(); idle = null
                try { runOne(jpegPath) } finally { if (loaded) scheduleIdleRelease() }
            }
        }
    }

    override suspend fun release() {
        withContext(dispatcher) { mutex.withLock { idle?.cancel(); idle = null; releaseLocked() } }
    }

    /** Whether the model is in memory right now (tests and the Developer screen). */
    suspend fun isLoaded(): Boolean = mutex.withLock { loaded }

    private fun runOne(path: String): Result<String> {
        device().let { CaptionPolicy.refusal(it) }?.let { return refused(it) }
        val files = models()?.takeIf { it.model.isFile && it.mmproj.isFile } ?: return refused(CaptionRefusal.MODEL_MISSING)
        val frame = frames.load(path, CaptionConfig.MAX_SIDE_PX) ?: return refused(CaptionRefusal.BAD_IMAGE)
        return try {
            if (!loaded) {
                if (engine.load(files, threads) != null) { releaseLocked(); return refused(CaptionRefusal.ENGINE_FAILED) }
                loaded = true
            }
            val text = engine.run(frame, CaptionConfig.PROMPT, CaptionConfig.MAX_TOKENS).trim()
            if (text.isEmpty()) refused(CaptionRefusal.ENGINE_FAILED) else Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) { // a native failure (or a missing library) is a refusal, not a crash; drop the possibly broken context
            releaseLocked()
            refused(CaptionRefusal.ENGINE_FAILED)
        }
    }

    private fun scheduleIdleRelease() {
        idle = scope.launch {
            delay(idleReleaseMs)
            mutex.withLock { releaseLocked() }
        }
    }

    private fun releaseLocked() {
        if (loaded) { loaded = false; try { engine.release() } catch (_: Throwable) { } }
    }

    private fun refused(r: CaptionRefusal): Result<String> = Result.failure(CaptionRefused(r))
}
