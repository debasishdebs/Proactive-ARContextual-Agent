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
import kotlinx.coroutines.flow.mapLatest
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
    /** Re-derives the list while Home collects it (gates that are not in the store: pause, zone hold, driving, quiet hours). */
    private val ticks: Flow<Unit> = flow { while (true) { emit(Unit); delay(TICK_MS) } },
) : SuggestionFeedSource {
    override val items: Flow<List<SuggestionCardUi>> = flow {
        val s = store()
        emitAll(combine(s.changes, settings.master, settings.placeRecall, settings.deliverPhone, ticks) { _, _, _, _, _ -> Unit }.mapLatest { build() })
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

    companion object { const val TICK_MS = 10_000L }
}
