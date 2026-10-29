package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `watch_app`: the PCA watch app (Galaxy Watch), spec 2026-10-29. It owns no sensor: `start()` publishes a [WatchMotionIntake]
 * in [WatchMotionIntake.current] for [PcaWatchListener] (Data Layer) and, until slice C, [WatchMotionProvider] to feed, `stop()` clears it. [sinkFor] is taken inside `start()` (per-run generation, see [ContextSource.start]).
 * The 1 s coalescing timer runs on a private single daemon thread that only calls the intake (which only enqueues).
 */
class WatchSource(
    private val settings: ContextSourceSettings,
    private val installed: () -> Boolean,
    private val sinkFor: (String) -> EvidenceSink,
    private val onRejected: () -> Unit = {},
    private val onEpochReset: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val mono: () -> Long = { System.nanoTime() / 1_000_000L },
    private val schedule: ((Long, () -> Unit) -> Unit)? = null,
    private val link: WatchLinkState = WatchLinkState.shared,
) : ContextSource {
    override val id = ID
    private val count = java.util.concurrent.atomic.AtomicInteger()
    override val events: Int get() = count.get()

    private var intake: WatchMotionIntake? = null
    private var timer: ScheduledExecutorService? = null

    override fun available(): Boolean = installed()

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        val sink = sinkFor(ID)
        val ses = if (schedule == null) Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PcCtxWatch").apply { isDaemon = true } } else null
        val sched: (Long, () -> Unit) -> Unit = schedule ?: { d, f -> try { ses!!.schedule({ try { f() } catch (_: Exception) { } }, d, TimeUnit.MILLISECONDS) } catch (_: Exception) { } }
        val i = WatchMotionIntake(
            enabled = { settings.enabled(ID) }, pinnedSha256 = { settings.liveFitCertSha256 }, clock = clock,
            sink = { e -> count.incrementAndGet(); (e as? Evidence.WatchMotion)?.let { link.onAccepted(it.state, it.atMs) }; sink.accept(e) },
            initialEpoch = settings.watchEpoch, initialSeq = settings.watchLastSeq,
            persist = settings::setWatchSeq, schedule = sched, mono = mono,
            onReject = { r -> link.onDrop(r); onRejected() }, onEpochReset = onEpochReset,
        )
        timer = ses; intake = i; WatchMotionIntake.current = i
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        val i = intake ?: return
        i.close()
        if (WatchMotionIntake.current === i) WatchMotionIntake.current = null
        timer?.shutdownNow(); timer = null; intake = null
    }

    companion object { const val ID = ContextSourceSettings.WATCH_APP }
}
