package com.rokid.context.orchestration.proactive

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.QuietHours
import com.rokid.context.orchestration.nudge.NudgeHud
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * v3.1 r3 P1-2: withdraw-all-suggestions on every surface when driving closes or quiet hours begin, even when Home is not collecting. [install] registers the listener once (production, `ContextApp`); it kills the live
 * claims ([SuggestionPublications.withdrawAll]) and takes every card off the HUD overlay holder and Nexus ([ProactiveEnvironment.surfaces]). The phone feed derives from the store and the same events through [SuggestionGateEvents.closes].
 * Logs: literal only.
 */
object SuggestionWithdrawal {
    private val listener: () -> Unit = {
        try { SuggestionPublications.shared.withdrawAll(); ProactiveEnvironment.surfaces.withdrawAll() } catch (e: Exception) { PcLog.i("PcProactive", "suggestion withdraw-all failed") }
    }
    fun install() { SuggestionGateEvents.addWithdrawListener(listener) }
    fun uninstall() { SuggestionGateEvents.removeWithdrawListener(listener) }
}

/**
 * The process-wide scheduler of the NEXT quiet-hours boundary, armed only while a suggestion is displayed ([arm], called by the delivery after a publication): one one-shot task, replaced by every arm, never repeating, no thread
 * while nothing is armed. When it fires it raises [SuggestionGateEvents.quietBoundary]. [disarm] cancels it.
 */
class QuietBoundaryWatch(
    /** Schedules [task] after [delayMs]; the handle cancels it. */
    private val schedule: (delayMs: Long, task: () -> Unit) -> AutoCloseable = ::scheduleOnWorker,
    private val fire: () -> Unit = { SuggestionGateEvents.quietBoundary() },
) {
    private val lock = Any()
    private var pending: AutoCloseable? = null

    /** Arms the one-shot for the next start of [hud]'s quiet window after [nowMs]; nothing is armed when quiet hours are off. */
    fun arm(hud: NudgeHud?, nowMs: Long) {
        val q = hud?.quietHours
        // v3.1 r5 P1-1: armed INSIDE an active window (the boundary passed after the last check): withdraw at once instead of scheduling tomorrow's start
        if (q != null && com.rokid.context.core.model.QuietWindow.occurrenceAt(q, nowMs, hud.zone) != null) { disarm(); try { fire() } catch (_: Exception) { }; return }
        val next = if (q == null) null else nextQuietStart(q, nowMs, hud.zone)
        synchronized(lock) {
            pending?.let { try { it.close() } catch (_: Exception) { } }
            pending = if (next == null) null else try { schedule((next - nowMs).coerceAtLeast(1L) + 50L) { synchronized(lock) { pending = null }; fire() } } catch (_: Exception) { null }
        }
    }

    fun disarm() { synchronized(lock) { pending?.let { try { it.close() } catch (_: Exception) { } }; pending = null } }
    fun isArmed(): Boolean = synchronized(lock) { pending != null }

    companion object {
        val shared = QuietBoundaryWatch()

        private val executor: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "pc-quiet-boundary").apply { isDaemon = true } }.apply { setRemoveOnCancelPolicy(true); setKeepAliveTime(5, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
        }
        private fun scheduleOnWorker(delayMs: Long, task: () -> Unit): AutoCloseable {
            val f: ScheduledFuture<*> = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return AutoCloseable { f.cancel(false) }
        }

        /** The next instant after [nowMs] at which the quiet window starts (null = no window). */
        fun nextQuietStart(q: QuietHours, nowMs: Long, zone: ZoneId): Long? {
            if (q.startMinute == q.endMinute || q.startMinute !in 0..1439 || q.endMinute !in 0..1439) return null
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return (0L..2L).map { d -> today.plusDays(d).atTime(q.startMinute / 60, q.startMinute % 60).atZone(zone).toInstant().toEpochMilli() }.firstOrNull { it > nowMs }
        }
    }
}
