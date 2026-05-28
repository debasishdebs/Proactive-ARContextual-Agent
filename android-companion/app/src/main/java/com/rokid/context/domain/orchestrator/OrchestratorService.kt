package com.rokid.context.domain.orchestrator

import com.rokid.context.domain.queue.JobEnvelope
import com.rokid.context.domain.queue.QueuePriority

class OrchestratorService(
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val stateLock = Any()
    private var currentProviderBlockState: ProviderBlockState = ProviderBlockState.Healthy

    val providerBlockState: ProviderBlockState
        get() = synchronized(stateLock) { currentProviderBlockState }

    private val retriggerQueueByKey = linkedMapOf<String, JobEnvelope>()

    fun onProviderBlocked(pending: List<JobEnvelope>): List<CanceledJob> {
        synchronized(stateLock) {
            currentProviderBlockState = ProviderBlockState.Blocked
            enqueueSystemJobsForRetrigger(pending)
            return pending.map { CanceledJob(job = it, reason = ProviderBlockState.REASON_PROVIDER_BLOCKED) }
        }
    }

    fun submitUserRequest(@Suppress("UNUSED_PARAMETER") job: JobEnvelope): SubmitResult {
        synchronized(stateLock) {
            return if (currentProviderBlockState == ProviderBlockState.Blocked) {
                SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED)
            } else {
                SubmitResult.Accepted
            }
        }
    }

    fun submitSystemRequest(job: JobEnvelope): SubmitResult {
        synchronized(stateLock) {
            return if (currentProviderBlockState == ProviderBlockState.Blocked) {
                if (isSystemQueue(job)) {
                    enqueueSystemJobsForRetrigger(listOf(job))
                    SubmitResult.Deferred
                } else {
                    SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED)
                }
            } else {
                SubmitResult.Accepted
            }
        }
    }

    fun onProviderRecovered(): List<JobEnvelope> {
        synchronized(stateLock) {
            currentProviderBlockState = ProviderBlockState.Healthy
            val replayWindowStartEpochMillis = nowEpochMillis() - RETRIGGER_WARM_START_WINDOW_MILLIS
            val jobsToRetrigger =
                retriggerQueueByKey.values.filter {
                    it.enqueuedAtEpochMillis >= replayWindowStartEpochMillis
                }
            retriggerQueueByKey.clear()
            return jobsToRetrigger
        }
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

    private fun enqueueSystemJobsForRetrigger(jobs: List<JobEnvelope>) {
        jobs
            .asSequence()
            .filter(::isSystemQueue)
            .forEach { retriggerQueueByKey.putIfAbsent(it.idempotencyKey, it) }
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

    companion object {
        private const val RETRIGGER_WARM_START_WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
