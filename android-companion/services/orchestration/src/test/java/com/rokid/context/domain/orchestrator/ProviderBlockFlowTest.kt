package com.rokid.context.domain.orchestrator

import com.rokid.context.domain.queue.JobEnvelope
import com.rokid.context.domain.queue.QueuePriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBlockFlowTest {

    @Test
    fun providerBlockCancelsAllPendingJobsWithReasonProviderBlocked() {
        val orchestrator = OrchestratorService()
        val pending =
            listOf(
                job(QueuePriority.READ, "read"),
                job(QueuePriority.SUMMARIZER, "summarize"),
                job(QueuePriority.RETRY, "retry"),
            )

        val canceled = orchestrator.onProviderBlocked(pending)

        assertEquals(
            listOf(
                OrchestratorService.CanceledJob(
                    job = pending[0],
                    reason = ProviderBlockState.REASON_PROVIDER_BLOCKED,
                ),
                OrchestratorService.CanceledJob(
                    job = pending[1],
                    reason = ProviderBlockState.REASON_PROVIDER_BLOCKED,
                ),
                OrchestratorService.CanceledJob(
                    job = pending[2],
                    reason = ProviderBlockState.REASON_PROVIDER_BLOCKED,
                ),
            ),
            canceled,
        )
        assertEquals(ProviderBlockState.Blocked, orchestrator.providerBlockState)
    }

    @Test
    fun userRequestsAreRejectedAndNotAutoRetriedWhileBlocked() {
        val orchestrator = OrchestratorService()
        orchestrator.onProviderBlocked(emptyList())

        val userRequest = job(QueuePriority.READ, "latest context")
        val rejected = orchestrator.submitUserRequest(userRequest)

        assertEquals(
            OrchestratorService.SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED),
            rejected,
        )
        assertTrue(orchestrator.onProviderRecovered().isEmpty())
    }

    @Test
    fun systemQueuesAutoRetriggerAfterRecovery() {
        val orchestrator = OrchestratorService()
        val pending =
            listOf(
                job(QueuePriority.SUMMARIZER, "summary"),
                job(QueuePriority.FRAME_INGEST, "frame"),
                job(QueuePriority.READ, "user read"),
            )

        orchestrator.onProviderBlocked(pending)
        val deferredSystemJob = job(QueuePriority.RETRY, "retry")
        val submitResult = orchestrator.submitSystemRequest(deferredSystemJob)

        assertEquals(OrchestratorService.SubmitResult.Deferred, submitResult)

        val retrigger = orchestrator.onProviderRecovered()

        assertEquals(
            listOf(
                pending[0],
                pending[1],
                deferredSystemJob,
            ),
            retrigger,
        )
        assertEquals(ProviderBlockState.Healthy, orchestrator.providerBlockState)
    }

    @Test
    fun nonSystemQueuesAreRejectedOnSystemSubmissionPathWhileBlocked() {
        val orchestrator = OrchestratorService()
        orchestrator.onProviderBlocked(emptyList())

        val readJob = job(QueuePriority.READ, "read")
        val writeJob = job(QueuePriority.EXPLICIT_WRITE, "write")

        assertEquals(
            OrchestratorService.SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED),
            orchestrator.submitSystemRequest(readJob),
        )
        assertEquals(
            OrchestratorService.SubmitResult.Rejected(ProviderBlockState.REASON_PROVIDER_BLOCKED),
            orchestrator.submitSystemRequest(writeJob),
        )
        assertTrue(orchestrator.onProviderRecovered().isEmpty())
    }

    @Test
    fun repeatedProviderBlockedEventsDoNotAccumulateDuplicateSystemRetriggers() {
        val orchestrator = OrchestratorService()
        val summarizer = job(QueuePriority.SUMMARIZER, "summary")
        val retry = job(QueuePriority.RETRY, "retry")
        val pending = listOf(summarizer, retry, job(QueuePriority.READ, "user read"))

        orchestrator.onProviderBlocked(pending)
        orchestrator.onProviderBlocked(pending)
        assertEquals(OrchestratorService.SubmitResult.Deferred, orchestrator.submitSystemRequest(summarizer))
        assertEquals(OrchestratorService.SubmitResult.Deferred, orchestrator.submitSystemRequest(retry))

        val retrigger = orchestrator.onProviderRecovered()

        assertEquals(listOf(summarizer, retry), retrigger)
    }

    @Test
    fun repeatedBlockRecoveryCyclesReplayEachSystemJobOnlyOncePerCycle() {
        val orchestrator = OrchestratorService()
        val retry = job(QueuePriority.RETRY, "retry")
        val summarize = job(QueuePriority.SUMMARIZER, "summary")

        orchestrator.onProviderBlocked(listOf(retry, retry))
        assertEquals(listOf(retry), orchestrator.onProviderRecovered())
        assertEquals(ProviderBlockState.Healthy, orchestrator.providerBlockState)

        orchestrator.onProviderBlocked(listOf(retry, summarize, summarize))
        assertEquals(listOf(retry, summarize), orchestrator.onProviderRecovered())
    }

    @Test
    fun recoveryRetriggersOnlyJobsInsideTenMinuteWarmStartWindow() {
        val now = 1_704_067_200_000L
        val orchestrator = OrchestratorService(nowEpochMillis = { now })
        val staleSystemJob = job(
            QueuePriority.SUMMARIZER,
            "stale",
            enqueuedAtEpochMillis = now - 601_000L,
        )
        val freshSystemJob = job(
            QueuePriority.RETRY,
            "fresh",
            enqueuedAtEpochMillis = now - 599_000L,
        )

        orchestrator.onProviderBlocked(
            listOf(
                staleSystemJob,
                freshSystemJob,
                job(QueuePriority.READ, "user"),
            ),
        )

        val retrigger = orchestrator.onProviderRecovered()

        assertEquals(listOf(freshSystemJob), retrigger)
    }

    private fun job(
        queuePriority: QueuePriority,
        payload: String,
        enqueuedAtEpochMillis: Long = System.currentTimeMillis(),
    ): JobEnvelope =
        JobEnvelope(
            queue = queuePriority,
            payload = payload,
            enqueuedAtEpochMillis = enqueuedAtEpochMillis,
        )
}
