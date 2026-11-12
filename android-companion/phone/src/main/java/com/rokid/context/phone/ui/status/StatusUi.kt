package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.PauseRecord
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
    /** Legacy switch only (see [CaptureLine.pausedOnGlasses]); always false with the Phase 7 pause control. */
    val pausedOnGlasses: Boolean = false,
    /** Every active banner, most severe first (Home's pager, ruling 2026-11-13); [banner] is the first of them. */
    val banners: List<BannerSpec> = listOfNotNull(banner),
) {
    companion object {
        val Initial = StatusUi("Glasses offline", live = false, paused = false, batteryPct = null, banner = null)

        fun build(
            s: GlassesStatus, pipelineEnabled: Boolean, storagePct: Int?, nowMs: Long, index: IndexState = IndexState.NONE,
            pauses: List<PauseRecord> = emptyList(), lastResumeAtMs: Long? = null, batterySaver: Boolean = false,
        ): StatusUi {
            val all = BannerResolver.candidates(
                BannerInputs(
                    aiBlockedText = (s.frame?.aiHealth as? Health.Blocked)?.text,
                    link = s.link,
                    capture = s.frame?.capture,
                    pipelineEnabled = pipelineEnabled,
                    index = index,
                    storagePct = storagePct,
                    pauses = pauses,
                    lastResumeAtMs = lastResumeAtMs,
                    nowMs = nowMs,
                    batterySaver = batterySaver,
                    glassesNote = s.note,
                ),
            )
            return StatusUi(
                captureText = CaptureLine.text(s, pipelineEnabled, nowMs, pauses),
                live = CaptureLine.live(s, pipelineEnabled),
                paused = CaptureLine.paused(s, pipelineEnabled),
                batteryPct = s.glassesBatteryPct ?: s.frame?.glassesBatteryPct,
                pipelineEnabled = pipelineEnabled,
                pausedOnGlasses = CaptureLine.pausedOnGlasses(s, pipelineEnabled),
                banner = all.firstOrNull(),
                banners = all,
            )
        }

        /** [batterySaver]: Home passes Android's Battery Saver (G7); other screens leave it off. */
        fun flow(
            glasses: GlassesStatusSource, pipeline: PipelineControl, clock: Flow<Long>, storagePct: Flow<Int?>, index: Flow<IndexState> = flowOf(IndexState.NONE),
            batterySaver: Flow<Boolean> = flowOf(false),
        ): Flow<StatusUi> =
            combine(
                combine(glasses.status, pipeline.enabled, clock, storagePct, index) { s, enabled, now, storage, idx -> Inputs(s, enabled, now, storage, idx) },
                pipeline.activePauses, pipeline.lastResumeAtMs, batterySaver,
            ) { i, pauses, resumedAt, saver -> build(i.s, i.enabled, i.storage, i.now, i.index, pauses, resumedAt, saver) }

        private data class Inputs(val s: GlassesStatus, val enabled: Boolean, val now: Long, val storage: Int?, val index: IndexState)
    }
}

/** RECONNECT and RESUME act here; FIX_AI (navigation) and REBUILD_INDEX (SettingsDeps.rebuildIndex) are handled by the route (Destinations.kt). */
object BannerActions {
    fun apply(action: BannerAction, glasses: GlassesStatusSource, pipeline: PipelineControl) {
        when (action) {
            BannerAction.RECONNECT -> glasses.requestReconnect()
            BannerAction.RESUME -> pipeline.setEnabled(true) // resumes the ids active now (spec §5.5)
            BannerAction.FIX_AI, BannerAction.REBUILD_INDEX -> Unit
        }
    }
}
