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

        assertEquals(pending.size, canceled.size)
        assertTrue(canceled.all { it.reason == ProviderBlockState.REASON_PROVIDER_BLOCKED })
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

    private fun job(queuePriority: QueuePriority, payload: String): JobEnvelope =
        JobEnvelope(
            queue = queuePriority,
            payload = payload,
            enqueuedAtEpochMillis = 1_704_067_200_000,
        )
}
