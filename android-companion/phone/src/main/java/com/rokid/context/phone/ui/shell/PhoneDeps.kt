package com.rokid.context.phone.ui.shell

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskActions
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.orchestration.PrefsIndexClock
import com.rokid.context.phone.ui.banner.IndexState
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.LegacyQueueActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.SchedulerDiagnosticsActions
import com.rokid.context.memory.MemorySettingsStore
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.status.CapturePauses
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.status.StorageUsage
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the screens need, built once per process (no DI framework in v1). ViewModels survive configuration
 * changes, so this must be a singleton too — otherwise a rotated activity would hold a second PipelineControl.
 */
class PhoneDeps(
    val memory: MemoryGraph,
    val glasses: GlassesStatusSource,
    val pipeline: PipelineControl,
    val onboarding: OnboardingStore,
    val askService: AskService,
    val rateAsk: suspend (String, Boolean) -> Boolean,
    /** M5: "Ask again" on an answer whose source was forgotten: its stored question through the normal typed Ask (a new answer row; the old one is marked asked again). Never automatic. */
    val askAgain: suspend (String) -> AskOutcome = { AskOutcome.Failed("Couldn't answer that \u2014 try again") },
    val diagnostics: DiagnosticsActions,
    val queues: QueueActions,
    val settings: SettingsDeps,
    val storagePct: Flow<Int?>,
    val clock: Flow<Long>,
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Banner 6 (spec §2.2): index rebuild needed / running, or unstable after repeated drift (Task 11). */
    val index: Flow<IndexState> = flowOf(IndexState.NONE),
    /** Home's phone-mic Ask (Phase 6). */
    val voice: com.rokid.context.phone.voice.PhoneVoice = com.rokid.context.phone.voice.PhoneVoice.None,
    /** RECORD_AUDIO was answered: re-check the voice gate. */
    val onMicGranted: () -> Unit = {},
    /** Activity's sort menu choice (also orders the Day screen); persisted by [create]. */
    val activitySort: com.rokid.context.phone.ui.activity.ActivitySortStore = com.rokid.context.phone.ui.activity.InMemoryActivitySort(),
    /** G7: Android Battery Saver (asks are paused while it is on). */
    val batterySaver: Flow<Boolean> = flowOf(false),
    /** G3: Home's limited-mode banner. */
    val limitedMode: com.rokid.context.phone.ui.home.LimitedModeControl = com.rokid.context.phone.ui.home.LimitedModeControl.None,
    /** Find a photo (wave 3): recall, describe on demand, stored paths and the Room change signal. */
    val photoSearch: com.rokid.context.phone.ui.photos.PhotoSearchDeps = com.rokid.context.phone.ui.photos.PhotoSearchDeps.None,
    /** v0.9.7 slice D: the shared utterance backend; the Home chat calls it before the ask (null in tests = every text is a plain ask). */
    val utterance: com.rokid.context.voice.Utterance? = null,
    /** v3.1: the delivered suggestions in Home's feed (default: none; every delivery switch is OFF on a fresh install). */
    val suggestionFeed: com.rokid.context.phone.ui.home.SuggestionFeedSource = com.rokid.context.phone.ui.home.SuggestionFeedSource.None,
) {
    fun today(): LocalDate = LocalDate.now(zone())

    companion object {
        @Volatile private var instance: PhoneDeps? = null

        fun get(context: Context): PhoneDeps = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        /** 1 Hz UI clock: re-formats the "next 4:12" countdown and notices local midnight. No I/O. Cold. */
        fun ticker(periodMs: Long = 1_000, now: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
            while (currentCoroutineContext().isActive) {
                emit(now())
                delay(periodMs)
            }
        }

        fun create(app: Context): PhoneDeps {
            val scheduler = OrchestrationScheduler(app)
            val voice = com.rokid.context.phone.voice.VoiceGraph.get(app)
            return PhoneDeps(
                memory = MemoryGraph.get(app),
                glasses = com.rokid.context.phone.hub.HubGraph.glassesStatus(app),
                pipeline = CapturePauses.control(app),
                onboarding = OnboardingStore(app),
                // Typed asks are mirrored to the HUD (spec §2.4) without being spoken, when the answer's DeliveryRoute puts it on the glasses.
                askService = AskService { q -> AskActions.ask(app, q).also { r -> com.rokid.context.phone.voice.mirrorTypedAnswer(r, com.rokid.context.orchestration.AskZoneFence::blocked) { voice.mirrorTyped(it) } } },
                rateAsk = { id, up -> AskActions.rate(app, id, up) },
                askAgain = { id -> com.rokid.context.orchestration.forget.ReanswerWorker.reask(app, id) { q -> AskActions.ask(app, q).also { r -> com.rokid.context.phone.voice.mirrorTypedAnswer(r, com.rokid.context.orchestration.AskZoneFence::blocked) { voice.mirrorTyped(it) } } } },
                diagnostics = SchedulerDiagnosticsActions(scheduler),
                queues = LegacyQueueActions(app, scheduler),
                settings = SettingsDeps.create(app),
                storagePct = StorageUsage.percentFlow(app) { MemorySettingsStore.get(app).settings.value.quotaBytes },
                clock = ticker(),
                index = combine(
                    MemoryGraph.get(app).spaces.health(),
                    PrefsIndexClock(app).observeUnstable(),
                    flow { emitAll(com.rokid.context.ai.AiGraph.get(app).bindings.observeAll()) }.map { b -> b.any { it.role == com.rokid.context.ai.Role.EMBED } },
                    PrefsIndexClock(app).observeBatteryPaused(), // Gaps G6
                    flow { emitAll(com.rokid.context.memory.retrieval.RetrievalSettingsStore.get(app).settings) }.map { it.mode != com.rokid.context.memory.retrieval.RetrievalMode.LEXICAL },
                ) { h, unstable, bound, battery, wanted -> IndexState.of(h, unstable, bound, battery, wanted) }
                    .flowOn(Dispatchers.IO), // the clock's first prefs read stays off Main
                voice = voice.phoneVoice,
                onMicGranted = voice::onForeground,
                activitySort = com.rokid.context.phone.ui.activity.PrefsActivitySort(app),
                batterySaver = com.rokid.context.orchestration.BatterySaver.observe(app),
                limitedMode = com.rokid.context.phone.ui.home.PrefsLimitedMode.forApp(app),
                photoSearch = com.rokid.context.phone.ui.photos.PhotoSearchDeps.create(app),
                utterance = voice.utterance,
                suggestionFeed = com.rokid.context.phone.ui.home.AndroidSuggestionFeed(app),
            )
        }
    }
}
