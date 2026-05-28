package com.rokid.context.domain.orchestrator

import com.rokid.context.domain.queue.JobEnvelope
import com.rokid.context.domain.queue.QueuePriority

class OrchestratorService {
    var providerBlockState: ProviderBlockState = ProviderBlockState.Healthy
        private set

    private val retriggerQueue = mutableListOf<JobEnvelope>()

    fun onProviderBlocked(pending: List<JobEnvelope>): List<CanceledJob> {
        providerBlockState = ProviderBlockState.Blocked
        retriggerQueue += pending.filter(::isSystemQueue)
        return pending.map { CanceledJob(job = it, reason = ProviderBlockState.REASON_PROVIDER_BLOCKED) }
    }

    fun submitUserRequest(job: JobEnvelope): SubmitResult =
        if (providerBlockState == ProviderBlockState.Blocked) {
            SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED)
        } else {
            SubmitResult.Accepted
        }

    fun submitSystemRequest(job: JobEnvelope): SubmitResult =
        if (providerBlockState == ProviderBlockState.Blocked) {
            if (isSystemQueue(job)) {
                retriggerQueue += job
                SubmitResult.Deferred
            } else {
                SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED)
            }
        } else {
            SubmitResult.Accepted
        }

    fun onProviderRecovered(): List<JobEnvelope> {
        providerBlockState = ProviderBlockState.Healthy
        val jobsToRetrigger = retriggerQueue.toList()
        retriggerQueue.clear()
        return jobsToRetrigger
    }

    private fun isSystemQueue(job: JobEnvelope): Boolean = when (job.queue) {
        QueuePriority.READ,
        QueuePriority.EXPLICIT_WRITE,
        -> false

        QueuePriority.PRIORITY_REPROCESS,
        QueuePriority.SUMMARIZER,
        QueuePriority.FRAME_INGEST,
        QueuePriority.RETRY,
        -> true
    }

    data class CanceledJob(
        val job: JobEnvelope,
        val reason: String,
    )

    sealed class SubmitResult {
        data object Accepted : SubmitResult()

        data object Deferred : SubmitResult()

        data class Rejected(val reason: String) : SubmitResult()
    }
}
