package com.rokid.context.phone.ui.shell

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskActions
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.LegacyQueueActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.ui.settings.SchedulerDiagnosticsActions
import com.rokid.context.phone.ui.status.LegacyPipelineControl
import com.rokid.context.phone.ui.status.PipelineControl
import com.rokid.context.phone.ui.status.StorageUsage
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    val diagnostics: DiagnosticsActions,
    val queues: QueueActions,
    val storagePct: Flow<Int?>,
    val clock: Flow<Long>,
    val zone: () -> ZoneId = { ZoneId.systemDefault() },
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
            return PhoneDeps(
                memory = MemoryGraph.get(app),
                glasses = com.rokid.context.phone.hub.HubGraph.glassesStatus(app),
                pipeline = LegacyPipelineControl(app, scheduler),
                onboarding = OnboardingStore(app),
                askService = AskService { q -> AskActions.ask(app, q) },
                rateAsk = { id, up -> AskActions.rate(app, id, up) },
                diagnostics = SchedulerDiagnosticsActions(scheduler),
                queues = LegacyQueueActions(app, scheduler),
                storagePct = StorageUsage.percentFlow(app),
                clock = ticker(),
            )
        }
    }
}
