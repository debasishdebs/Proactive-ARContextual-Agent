package com.rokid.context.domain.summary

import com.rokid.context.domain.queue.QueuePriority

class SummarizerWorker {
    fun shouldIncludeInReadContext(status: SummaryStatus): Boolean = status == SummaryStatus.TRUSTED

    fun reprocessQueuePriorityFor(status: SummaryStatus): QueuePriority? =
        if (status == SummaryStatus.NEEDS_REVIEW) QueuePriority.PRIORITY_REPROCESS else null

    fun shouldDeleteRawFrame(rawFrameCreatedAtMs: Long, nowMs: Long, criticValidated: Boolean): Boolean {
        if (criticValidated) return true
        val twentyFourHoursMs = 24L * 60L * 60L * 1000L
        return nowMs - rawFrameCreatedAtMs >= twentyFourHoursMs
    }
}
