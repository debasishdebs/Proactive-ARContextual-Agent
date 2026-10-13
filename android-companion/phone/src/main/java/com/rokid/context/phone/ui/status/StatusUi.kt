package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.Health
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerInputs
import com.rokid.context.phone.ui.banner.BannerResolver
import com.rokid.context.phone.ui.banner.BannerSpec
import com.rokid.context.phone.ui.banner.IndexState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Shared status block for Home and Activity: subline/strip text, live dot, pause state, battery, top banner. */
data class StatusUi(
    val captureText: String,
    val live: Boolean,
    val paused: Boolean,
    val batteryPct: Int?,
    val banner: BannerSpec?,
    /** The phone pipeline switch: the only thing the ⏸/▶ control reads and changes. */
    val pipelineEnabled: Boolean = true,
    /** The glasses paused capture while the pipeline is on: shown, not actionable (phone→glasses Resume is Phase 7). */
    val pausedOnGlasses: Boolean = false,
) {
    companion object {
        val Initial = StatusUi("Glasses offline", live = false, paused = false, batteryPct = null, banner = null)

        fun build(s: GlassesStatus, pipelineEnabled: Boolean, storagePct: Int?, nowMs: Long, index: IndexState = IndexState.NONE) = StatusUi(
            captureText = CaptureLine.text(s, pipelineEnabled, nowMs),
            live = CaptureLine.live(s, pipelineEnabled),
            paused = CaptureLine.paused(s, pipelineEnabled),
            batteryPct = s.glassesBatteryPct ?: s.frame?.glassesBatteryPct,
            pipelineEnabled = pipelineEnabled,
            pausedOnGlasses = CaptureLine.pausedOnGlasses(s, pipelineEnabled),
            banner = BannerResolver.resolve(
                BannerInputs(
                    aiBlockedText = (s.frame?.aiHealth as? Health.Blocked)?.text,
                    link = s.link,
                    capture = s.frame?.capture,
                    pipelineEnabled = pipelineEnabled,
                    index = index,
                    storagePct = storagePct,
                ),
            ),
        )

        fun flow(glasses: GlassesStatusSource, pipeline: PipelineControl, clock: Flow<Long>, storagePct: Flow<Int?>, index: Flow<IndexState> = flowOf(IndexState.NONE)): Flow<StatusUi> =
            combine(glasses.status, pipeline.enabled, clock, storagePct, index) { s, enabled, now, storage, idx -> build(s, enabled, storage, now, idx) }
    }
}

/** RECONNECT and RESUME act here; FIX_AI (navigation) and REBUILD_INDEX (SettingsDeps.rebuildIndex) are handled by the route (Destinations.kt). */
object BannerActions {
    fun apply(action: BannerAction, glasses: GlassesStatusSource, pipeline: PipelineControl) {
        when (action) {
            BannerAction.RECONNECT -> glasses.requestReconnect()
            BannerAction.RESUME -> pipeline.setEnabled(true)
            BannerAction.FIX_AI, BannerAction.REBUILD_INDEX -> Unit
        }
    }
}
