package com.rokid.context.phone.context

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Data Layer view of the watch: connected nodes and how many of them have the PCA watch app (capability `pca_watch`). Counts only, no node names. [failed] = the query errored. */
data class WatchNodes(val connected: Int, val withApp: Int, val failed: Boolean = false)

fun interface WatchNodeProbe { suspend fun nodes(): WatchNodes? }

/** The real probe: `NodeClient.getConnectedNodes` plus `CapabilityClient.getCapability("pca_watch", FILTER_REACHABLE)`. Only called while the status row is visible and the source is on. */
class WearWatchNodeProbe(context: Context) : WatchNodeProbe {
    private val app = context.applicationContext
    override suspend fun nodes(): WatchNodes = try {
        val connected = Wearable.getNodeClient(app).connectedNodes.await().size
        val withApp = Wearable.getCapabilityClient(app).getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes.size
        WatchNodes(connected, withApp)
    } catch (c: kotlin.coroutines.cancellation.CancellationException) {
        throw c
    } catch (_: Exception) {
        WatchNodes(0, 0, failed = true)
    }

    companion object { const val CAPABILITY = "pca_watch" }
}

enum class WatchStatusKind { OFF, NOT_INSTALLED, UNREACHABLE, NO_DATA, LAST_MOTION }
data class WatchStatusRow(val kind: WatchStatusKind, val text: String)

/**
 * The Context sources watch status row (UI only). The 15 min line is fixed and never changes what the hub does with a watch value:
 * the hub's own rule is `watchTtlMs` (ContextHub passes 1_500_000 ms = 25 min; FusionParams default 30 min).
 * [nodes] null = not probed yet, so the line comes from the last accepted message alone.
 */
object WatchStatusModel {
    const val QUIET_MS = 15 * 60_000L

    fun of(sourceOn: Boolean, nodes: WatchNodes?, snap: WatchLinkSnapshot, nowMs: Long, time: (Long) -> String): WatchStatusRow {
        if (!sourceOn) return WatchStatusRow(WatchStatusKind.OFF, "")
        val at = snap.lastAcceptedAtMs
        val recent = at != null && nowMs - at <= QUIET_MS
        if (nodes != null && !nodes.failed && nodes.withApp == 0) return WatchStatusRow(WatchStatusKind.NOT_INSTALLED, "Watch app not installed or not paired")
        if (nodes != null && nodes.failed && !recent) return WatchStatusRow(WatchStatusKind.UNREACHABLE, "Watch app not reachable")
        if (recent) return WatchStatusRow(WatchStatusKind.LAST_MOTION, "Last motion: ${snap.lastState?.name ?: "UNKNOWN"}, ${time(at!!)}")
        return WatchStatusRow(WatchStatusKind.NO_DATA, "Watch: no data in the last 15 min")
    }
}

/** The setup step and the "Check the watch link" card: waiting until the first `/pca/motion` message is accepted in this process. It never blocks anything. */
object WatchLinkStep {
    const val WAITING = "Waiting for the watch…"
    const val CONFIRMED = "Watch link confirmed"
    fun confirmed(snap: WatchLinkSnapshot) = snap.accepted > 0
    fun line(confirmed: Boolean) = if (confirmed) CONFIRMED else WAITING
}
