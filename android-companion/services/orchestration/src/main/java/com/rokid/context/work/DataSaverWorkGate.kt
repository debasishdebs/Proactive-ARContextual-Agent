package com.rokid.context.work

import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.Skipped

/**
 * The roaming data saver for background workers that describe stored photos or backfill remote embeddings (night job, its catch-up, suspect sweep). [open] is the run-time check every
 * batch of such a worker asks next to its battery and heat gate; [atStart] asks once per run and, when the run cannot go, counts it (a count and a reason token in the PcData line, no content).
 * Nothing is lost by waiting: an undescribed photo is never deleted and is queued again on the next run.
 * The WorkManager constraint (`OrchestrationScheduler.dataSaverNetwork`) only saves the wake-up; this check is the real gate, because the constraint cannot know about roaming or the abroad switch.
 */
internal object DataSaverWorkGate {
    fun open(g: DataGate): Boolean = g.decide(DataKind.BULK_OTHER).allowed

    fun atStart(g: DataGate): Boolean {
        val d = g.decide(DataKind.BULK_OTHER)
        if (!d.allowed) g.skipped(DataKind.BULK_OTHER, Skipped.BULK, d)
        return d.allowed
    }
}
