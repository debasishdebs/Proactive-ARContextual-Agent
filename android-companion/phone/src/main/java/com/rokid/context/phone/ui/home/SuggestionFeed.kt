package com.rokid.context.phone.ui.home

import android.content.Context
import com.rokid.context.orchestration.proactive.ProactiveGraph
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.Surface
import com.rokid.context.orchestration.proactive.SuggestionActions
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.SuggestionStore
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.phone.ui.settings.suggestions.SuggestionsModel
import com.rokid.context.phone.ui.settings.suggestions.WhyUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * The phone feed IS the store: a record delivered to the phone (and not withdrawn) is a card. Every change of the store (a dismissal from any surface, a Forget, Delete all) and of the switches re-derives the list,
 * so a withdrawn card and its text vanish at once. A record whose sources can no longer be rendered is not shown. With the master, family or phone switch off nothing is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSuggestionFeed(
    private val app: Context,
    private val store: () -> SuggestionStore = { ProactiveGraph.store(app) },
    private val settings: ProactiveSettings = ProactiveSettings.of(app),
    private val render: suspend (SuggestionRecord, Long) -> RenderedCard? = { r, now -> ProactiveGraph.generator(app).render(r, now) },
    private val actions: SuggestionActions = ProactiveGraph.actions(app),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) : SuggestionFeedSource {
    override val items: Flow<List<SuggestionCardUi>> = flow {
        val s = store()
        emitAll(combine(s.changes, settings.master, settings.placeRecall, settings.deliverPhone) { _, _, _, _ -> Unit }.mapLatest { build(s) })
    }.flowOn(Dispatchers.IO)

    private suspend fun build(s: SuggestionStore): List<SuggestionCardUi> {
        if (!settings.deliversTo(Surface.PHONE)) return emptyList()
        val now = clock(); val z = zone()
        return s.records().filter { Surface.PHONE.token in it.delivered }.mapNotNull { r ->
            render(r, now)?.let { c -> SuggestionCardUi(r.id, r.ts, c.title.ifEmpty { "Suggestion" }, c.line, c.sourceLabel, SuggestionsModel.why(r, c, z)) }
        }
    }

    override fun dismiss(id: String) { actions.dismiss(id) }
    override fun snooze(id: String) { actions.snooze(id) }
    override fun never(id: String) { actions.never(id) }
    override fun thumb(id: String, up: Boolean) { actions.thumb(id, up) }
}
