package com.rokid.context.work

import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped

/**
 * The roaming data saver for background workers that describe stored photos or backfill remote embeddings (night job, its catch-up, suspect sweep). [open] is the run-time check every
 * batch of such a worker asks next to its battery and heat gate; [atStart] asks once per run and, when the run cannot go, counts it (a count and a reason token in the PcData line, no content).
 * Nothing is lost by waiting: an undescribed photo is never deleted and is queued again on the next run.
 * The WorkManager constraint (`OrchestrationScheduler.dataSaverNetwork`) only saves the wake-up; this check is the real gate, because the constraint cannot know about roaming or the abroad switch.
 */
internal object DataSaverWorkGate {
    /** [reach] is where the photo calls really go (the vision binding and its fallback, see KeyframeGraph.photoReach): an on-device or LAN endpoint is not a data cost and is not refused by Offline only. */
    fun open(g: DataGate, reach: EndpointReach = EndpointReach.REMOTE): Boolean = g.decide(DataKind.BULK_OTHER, reach).allowed

    fun atStart(g: DataGate, reach: EndpointReach = EndpointReach.REMOTE): Boolean {
        val d = g.decide(DataKind.BULK_OTHER, reach)
        if (!d.allowed) g.skipped(DataKind.BULK_OTHER, Skipped.BULK, d)
        return d.allowed
    }
}
