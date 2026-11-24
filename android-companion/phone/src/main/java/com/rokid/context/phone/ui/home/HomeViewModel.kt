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
import com.rokid.context.voice.Utterance
import com.rokid.context.voice.UtteranceResult
import com.rokid.context.voice.UtteranceSource
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    private val timeline: TimelineRepository,
    private val asks: AskRepository,
    nudges: NudgeRepository,
    private val askService: AskService,
    clock: Flow<Long>,
    storagePct: Flow<Int?>,
    private val zone: () -> ZoneId,
    index: Flow<IndexState> = flowOf(IndexState.NONE),
    private val voice: PhoneVoice = PhoneVoice.None,
    /** G7: Android Battery Saver; while on, Home's banner says asks are paused (the ask path refuses them). */
    batterySaver: Flow<Boolean> = flowOf(false),
    /** Retention v3 (K5): the one-time notice banner. */
    retentionNotice: Flow<Boolean> = flowOf(false),
    /** G3: the "Some features are limited" banner. */
    private val limitedMode: LimitedModeControl = LimitedModeControl.None,
    /** v0.9.7 slice D: the shared text backend, called with the chat text before any model call; null = every text is a plain ask. This class has no command logic of its own. */
    private val utterance: Utterance? = null,
    /** M5: "Ask again" on an answer whose source was forgotten: the user's tap runs its stored question through the normal Ask path (never automatic). */
    private val askAgainOf: suspend (String) -> AskOutcome = { AskOutcome.Failed(AskActions.FAILED) },
    /** v3.1: delivered suggestions (the default shows none: every delivery switch is OFF on a fresh install). */
    private val suggestions: SuggestionFeedSource = SuggestionFeedSource.None,
    /** B2: the newest place card of the phone-only card history; the default shows none. */
    private val placeCards: PlaceCardHomeSource = PlaceCardHomeSource.None,
) : ViewModel() {
    private data class Local(val question: String = "", val asking: Boolean = false, val error: String? = null, val lastAskId: String? = null, val reply: String? = null)

    private val local = MutableStateFlow(Local())

    private val micBlocked = MutableStateFlow(false)

    init {
        // A phone-mic answer shows inline, like a typed one (spec §2.4); one from before this screen opened never does.
        val before = voice.state.value.lastAskId
        viewModelScope.launch {
            voice.state.map { it.lastAskId }.filterNotNull().distinctUntilChanged().filter { it != before }
                .collect { id -> local.update { it.copy(lastAskId = id) } }
        }
        // A permanent denial holds only while the gate still needs the permission (the resume re-check clears it).
        viewModelScope.launch { voice.state.collect { if (it.status != VoiceStatus.NEEDS_MIC_PERMISSION) micBlocked.value = false } }
    }
    private val today = clock.map { DayBucket.dayOf(it, zone()) }.distinctUntilChanged()

    private val feed: Flow<List<FeedItem>> =
        combine(asks.recent(30), nudges.recent(30), today, suggestions.items) { a, n, d, g -> Feed.build(a, n, d, zone(), suggestions = g) }

    private val lastAnswer: Flow<FeedItem.Ask?> = combine(local.map { it.lastAskId }.distinctUntilChanged(), today) { id, d -> id to d }
        .flatMapLatest { (id, d) -> if (id == null) flowOf(null) else asks.observe(id).map { a -> a?.let { Feed.ask(it, d, zone()) } } }

    private val base: Flow<HomeUiState> = combine(
        StatusUi.flow(glasses, pipeline, clock, storagePct, index, batterySaver, retentionNotice),
        timeline.recentVisibleMemories(20).map(SuggestedQuestions::from),
        feed,
        lastAnswer,
        local,
    ) { status, suggestions, items, last, l ->
        HomeUiState(status, suggestions, items, l.question, l.asking, l.error, last, reply = l.reply)
    }

    /** Live: Room re-emits on every frame insert; a new local day (or zone) re-queries. */
    private val photosToday: Flow<Int> = clock.map { DayBucket.dayOf(it, zone()) to zone() }.distinctUntilChanged()
        .flatMapLatest { (d, z) -> timeline.frameTimesIn(DayBucket.bounds(d, z)).map { it.size } }.distinctUntilChanged()

    val state: StateFlow<HomeUiState> = combine(base, voice.state, micBlocked, photosToday, limitedMode.show) { s, v, blocked, n, limited ->
        s.copy(voice = HomeVoiceUi.from(v, blocked), photosToday = n, limited = limited)
    }.combine(placeCards.last) { s, p -> s.copy(lastPlaceCard = p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun suggestionDismiss(id: String) = suggestions.dismiss(id)
    fun suggestionSnooze(id: String) = suggestions.snooze(id)
    fun suggestionNever(id: String) = suggestions.never(id)
    fun suggestionThumb(id: String, up: Boolean) = suggestions.thumb(id, up)

    fun onQuestionChange(text: String) = local.update { it.copy(question = text.take(500), error = null, reply = null) }

    fun ask(question: String = local.value.question) {
        val q = question.trim()
        if (q.isEmpty() || local.value.asking) return
        local.update { it.copy(question = q, asking = true, error = null, reply = null) }
        viewModelScope.launch {
            try {
                // the shared backend first: a handled text is answered here with its short fixed reply (no model call; an undo waits for its removal); the unmatched clauses of a mixed text are all that is asked
                val toAsk = when (val u = utterance?.handle(q, UtteranceSource.PHONE_CHAT, genuineFinal = true) ?: UtteranceResult.PassThrough) {
                    UtteranceResult.PassThrough -> q
                    is UtteranceResult.Handled -> { local.update { it.copy(question = "", asking = false, error = null, reply = u.replyText) }; return@launch }
                    is UtteranceResult.Ask -> u.question
                }
                when (val r = askService.ask(toAsk)) {
                    is AskOutcome.Answered -> local.update { it.copy(question = "", asking = false, lastAskId = r.askId, reply = r.notice) } // the semantic-off status, if any
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

    /** M5: the user tapped "Ask again" on [askId]; a new answer row is saved by the normal path, the old row says it was asked again. Plain text only, never through the command handler (a command is not run twice). */
    fun askAgain(askId: String) {
        if (local.value.asking) return
        local.update { it.copy(asking = true, error = null, reply = null) }
        viewModelScope.launch {
            try {
                when (val r = askAgainOf(askId)) {
                    is AskOutcome.Answered -> local.update { it.copy(asking = false, lastAskId = r.askId, reply = r.notice) }
                    is AskOutcome.Failed -> local.update { it.copy(asking = false, error = r.message) }
                }
            } catch (c: CancellationException) {
                local.update { it.copy(asking = false) }
                throw c
            } catch (e: Exception) {
                local.update { it.copy(asking = false, error = AskActions.FAILED) }
            }
        }
    }

    /** The hero mic: start listening, or send what was heard (works while capture is paused: Ask is a user action). */
    fun onMic() {
        val v = voice.state.value
        when {
            v.listening -> voice.stop()
            v.thinking -> voice.cancel()
            v.settingsLoading || v.micOff || v.status != VoiceStatus.SUPPORTED -> Unit // the screen asks for a missing permission
            else -> voice.start()
        }
    }

    /** RECORD_AUDIO was refused; [permanent] = the system will not ask again, so Home points at the app's settings. */
    fun onMicDenied(permanent: Boolean) { if (permanent) micBlocked.value = true }

    /** Leaving Home cancels a phone ask still being spoken; one already thinking finishes (saved and spoken as usual). */
    fun onLeave() { if (voice.state.value.listening) voice.cancel() }

    override fun onCleared() = onLeave()

    /** Home resumed (back from the system's permission page): re-read what is limited. */
    fun onResume() { viewModelScope.launch { limitedMode.refresh() } }

    fun dismissLimited() = limitedMode.dismiss()

    fun togglePause() = pipeline.toggle()

    fun onBannerAction(action: BannerAction) = BannerActions.apply(action, glasses, pipeline)
}
