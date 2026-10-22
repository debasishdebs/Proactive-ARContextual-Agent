package com.rokid.context.phone.ui.settings.capture

import com.rokid.context.canvas.plan.ActivePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Settings > Capture's view of the canvas capture plan (S12, S26): the active plan (null = settings run the capture) and the "Use settings" action. The composition task binds it to
 * the process-wide [com.rokid.context.canvas.plan.CapturePlanPublisher]; until then [None] shows no plan row.
 */
interface CapturePlanControl {
    val active: Flow<ActivePlan?>
    suspend fun useSettings()

    object None : CapturePlanControl {
        override val active: Flow<ActivePlan?> = flowOf(null)
        override suspend fun useSettings() = Unit
    }
}
