package com.rokid.context.phone.ui.home

import android.content.Context
import com.rokid.context.orchestration.proactive.ProactiveDelivery
import com.rokid.context.orchestration.proactive.ProactiveGraph
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.Surface
import com.rokid.context.orchestration.proactive.SuggestionActions
import com.rokid.context.orchestration.proactive.SuggestionStore
import com.rokid.context.phone.ui.settings.suggestions.SuggestionsModel
import com.rokid.context.phone.ui.settings.suggestions.WhyUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformLatest
import com.rokid.context.orchestration.proactive.SuggestionGateEvents
import java.time.ZoneId

/** One delivered suggestion as the phone feed shows it: the template text rendered from the LIVE sources just now, never stored. */
data class SuggestionCardUi(val id: String, val atMs: Long, val title: String, val line: String, val sourceLabel: String, val why: WhyUi)

/** What Home needs from the suggestions loop (production: [AndroidSuggestionFeed]; the default shows nothing and does nothing, so a fresh install behaves as before). */
interface SuggestionFeedSource {
    val items: Flow<List<SuggestionCardUi>>
    fun dismiss(id: String)
    fun snooze(id: String)
    fun never(id: String)
    fun thumb(id: String, up: Boolean)

    companion object {
        val None = object : SuggestionFeedSource {
            override val items: Flow<List<SuggestionCardUi>> = flowOf(emptyList())
            override fun dismiss(id: String) {}
            override fun snooze(id: String) {}
            override fun never(id: String) {}
            override fun thumb(id: String, up: Boolean) {}
        }
    }
}

/**
 * The phone feed IS the store, behind the SAME fences as a delivery (Codex v3.1 r1 P1-2): every render and re-render asks [cards] (production: `ProactiveDelivery.phoneFeed`: the three switches, the read lease, provenance of
 * every source, the place's zone and the zone fence, pause, driving (UNKNOWN is driving) and quiet hours with a fresh clock, all inside the fences). Any gate closed = an EMPTY list, so the rendered state is cleared
 * and no previously delivered text stays on screen. Every change of the store, of the switches and a periodic tick (a pause or a zone hold changes no store) re-derives the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSuggestionFeed(
    private val app: Context,
    private val store: () -> SuggestionStore = { ProactiveGraph.store(app) },
    private val settings: ProactiveSettings = ProactiveSettings.of(app),
    private val cards: suspend (Long) -> List<ProactiveDelivery.FeedCard> = { now -> ProactiveGraph.delivery(app).phoneFeed(now) },
    private val actions: SuggestionActions = ProactiveGraph.actions(app),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** The BACKSTOP: re-derives the list while Home collects it (the gates that have no event of their own, e.g. calm evidence aging out or Battery Saver). Never the primary clearing path. */
    private val ticks: Flow<Unit> = flow { while (true) { emit(Unit); delay(TICK_MS) } },
    /**
     * v3.1 r2 P1-2: the gate-CLOSE events (a zone approached or held, a pause added, driving or unknown started, the quiet-hours boundary): each one clears the rendered list at once and then re-derives, it does not wait
     * for [ticks]. Production: [SuggestionGateEvents.closes] and the quiet-hours timer. Cold: nothing runs unless Home collects.
     */
    private val gateEvents: Flow<Unit> = defaultGateEvents(),
) : SuggestionFeedSource {
    override val items: Flow<List<SuggestionCardUi>> = flow {
        val s = store()
        val switches = combine(settings.master, settings.placeRecall, settings.deliverPhone) { _, _, _ -> true }.drop(1) // the first value is the current state, not a change
        val triggers: Flow<Boolean> = merge(s.changes.map { false }, ticks.map { false }, switches, gateEvents.map { true })
        emitAll(triggers.transformLatest { close ->
            if (close) emit(emptyList()) // a gate closed (or a switch changed): nothing stays rendered while the list is derived again
            emit(build())
        })
    }.flowOn(Dispatchers.IO)

    private suspend fun build(): List<SuggestionCardUi> {
        if (!settings.deliversTo(Surface.PHONE)) return emptyList()
        val z = zone()
        return cards(clock()).map { (r, c) -> SuggestionCardUi(r.id, r.ts, c.title.ifEmpty { "Suggestion" }, c.line, c.sourceLabel, SuggestionsModel.why(r, c, z)) }
    }

    override fun dismiss(id: String) { actions.dismiss(id) }
    override fun snooze(id: String) { actions.snooze(id) }
    override fun never(id: String) { actions.never(id) }
    override fun thumb(id: String, up: Boolean) { actions.thumb(id, up) }

    companion object {
        const val TICK_MS = 10_000L

        /** The production close events: the process-wide [SuggestionGateEvents] (zone, pause, driving) and the quiet-hours boundary timer. */
        fun defaultGateEvents(
            hud: () -> com.rokid.context.orchestration.nudge.NudgeHud? = { com.rokid.context.orchestration.nudge.NudgeEnvironment.hud?.invoke() },
            clock: () -> Long = System::currentTimeMillis,
        ): Flow<Unit> = merge(SuggestionGateEvents.closes.drop(1).map { }, quietBoundaries(hud, clock)) // drop(1): the current value is not an event

        /** Emits at every START of the quiet window (the moment a gate closes); idles (re-reading the settings once a minute) while quiet hours are off. Cancelled with the collector: no timer outlives Home. */
        fun quietBoundaries(hud: () -> com.rokid.context.orchestration.nudge.NudgeHud?, clock: () -> Long = System::currentTimeMillis): Flow<Unit> = flow {
            while (true) {
                val h = try { hud() } catch (e: Exception) { null }
                val q = h?.quietHours
                val now = clock()
                val next = if (q == null) null else nextQuietStart(q, now, h.zone)
                if (next == null) delay(IDLE_MS) else { delay((next - now).coerceIn(1L, IDLE_MS * 60) + 50L); if (clock() >= next) emit(Unit) }
            }
        }

        private const val IDLE_MS = 60_000L

        /** The next instant after [nowMs] at which the quiet window starts (null = no window). */
        internal fun nextQuietStart(q: com.rokid.context.core.model.QuietHours, nowMs: Long, zone: ZoneId): Long? {
            if (q.startMinute == q.endMinute || q.startMinute !in 0..1439 || q.endMinute !in 0..1439) return null
            val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return (0L..2L).map { d -> today.plusDays(d).atTime(q.startMinute / 60, q.startMinute % 60).atZone(zone).toInstant().toEpochMilli() }.firstOrNull { it > nowMs }
        }
    }
}
