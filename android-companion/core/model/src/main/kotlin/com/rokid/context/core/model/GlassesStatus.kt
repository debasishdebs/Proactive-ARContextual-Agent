// android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/GlassesStatus.kt
package com.rokid.context.core.model

import kotlinx.coroutines.flow.StateFlow

/** Phone-side view of the glasses link, produced by Phase 3's hub, consumed by Phase 4's UI. */
enum class LinkState { NOT_AUTHORIZED, CONNECTING, CONNECTED, PAUSED_BT, CLOSED_ON_GLASSES, VERSION_MISMATCH, OFFLINE }

data class GlassesStatus(
    val link: LinkState,
    val glassesBatteryPct: Int?,
    val bufferedFrames: Int,
    val lastFrameAtMs: Long?,
    val frame: StateFrame?,        // last StateFrame the phone broadcast (phone is source of truth)
)

interface GlassesStatusSource {
    val status: StateFlow<GlassesStatus>
    fun requestReconnect()         // explicit phone action ("Open on glasses"), per spec §1.7 reconnect policy
}
