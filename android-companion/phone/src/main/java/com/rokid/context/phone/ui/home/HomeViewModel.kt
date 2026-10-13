package com.rokid.context.phone.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.AskRepository
import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.NudgeRepository
import com.rokid.context.memory.TimelineRepository
import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.status.BannerActions
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.banner.IndexState
import com.rokid.context.phone.ui.status.StatusUi
import com.rokid.context.phone.voice.PhoneVoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val glasses: GlassesStatusSource,
    private val pipeline: PipelineControl,
    timeline: TimelineRepository,
    private val asks: AskRepository,
    nudges: NudgeRepository,
    private val askService: AskService,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
    index: Flow<IndexState> = flowOf(IndexState.NONE),
    private val voice: PhoneVoice = PhoneVoice.None,
) : ViewModel() {
    private data class Local(val question: String = "", val asking: Boolean = false, val error: String? = null, val lastAskId: String? = null)

    private val local = MutableStateFlow(Local())

    init {
        // A phone-mic answer shows inline, like a typed one (spec §2.4).
        viewModelScope.launch { voice.state.map { it.lastAskId }.filterNotNull().distinctUntilChanged().collect { id -> local.update { it.copy(lastAskId = id) } } }
    }
    private val today = clock.map { DayBucket.dayOf(it, zone()) }.distinctUntilChanged()

    private val feed: Flow<List<FeedItem>> =
        combine(asks.recent(30), nudges.recent(30), today) { a, n, d -> Feed.build(a, n, d, zone()) }

    private val lastAnswer: Flow<FeedItem.Ask?> = combine(local.map { it.lastAskId }.distinctUntilChanged(), today) { id, d -> id to d }
        .flatMapLatest { (id, d) -> if (id == null) flowOf(null) else asks.observe(id).map { a -> a?.let { Feed.ask(it, d, zone()) } } }

    private val base: Flow<HomeUiState> = combine(
        StatusUi.flow(glasses, pipeline, clock, storagePct, index),
        timeline.recentVisibleMemories(20).map(SuggestedQuestions::from),
        feed,
        lastAnswer,
        local,
    ) { status, suggestions, items, last, l ->
        HomeUiState(status, suggestions, items, l.question, l.asking, l.error, last)
    }

    val state: StateFlow<HomeUiState> = combine(base, voice.state) { s, v -> s.copy(voice = HomeVoiceUi.from(v)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onQuestionChange(text: String) = local.update { it.copy(question = text.take(500), error = null) }

    fun ask(question: String = local.value.question) {
        val q = question.trim()
        if (q.isEmpty() || local.value.asking) return
        local.update { it.copy(question = q, asking = true, error = null) }
        viewModelScope.launch {
            try {
                when (val r = askService.ask(q)) {
                    is AskOutcome.Answered -> local.update { it.copy(question = "", asking = false, lastAskId = r.askId) }
                    is AskOutcome.Failed -> local.update { it.copy(asking = false, error = r.message) }
                }
            } catch (c: CancellationException) {
                local.update { it.copy(asking = false) } // Phase 2 carry: never left "asking" after a cancel
                throw c
            } catch (e: Exception) {
                local.update { it.copy(asking = false, error = AskActions.FAILED) }
            }
        }
    }

    /** The hero mic: start listening, or send what was heard (works while capture is paused: Ask is a user action). */
    fun onMic() { if (voice.state.value.listening) voice.stop() else voice.start() }

    fun togglePause() = pipeline.toggle()

    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
