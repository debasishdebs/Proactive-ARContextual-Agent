package com.rokid.context.model

import com.rokid.context.model.lifecycle.DownloadState
import com.rokid.context.model.lifecycle.ModelDownloadManager
import com.rokid.context.model.lifecycle.ModelSlotManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSlotManagerTest {
    private val slotManager = ModelSlotManager()
    private val downloadManager = ModelDownloadManager()

    @Test
    fun downloadPausesOnLowBatteryAndNotCharging() {
        val next = downloadManager.nextState(
            current = DownloadState.RUNNING,
            batteryPercent = 20,
            isCharging = false,
        )
        assertEquals(DownloadState.PAUSED, next)
    }

    @Test
    fun candidatePromotionRequiresHealthPass() {
        assertTrue(slotManager.canPromoteCandidate(healthPass = true))
        assertFalse(slotManager.canPromoteCandidate(healthPass = false))
    }

    @Test
    fun fallbackToCloudWhenLocalUnhealthy() = runBlocking {
        val cloudProvider = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "cloud:$prompt"
        }
        val localProvider = object : ModelProvider {
            override suspend fun generate(prompt: String): String = "local:$prompt"
        }

        val gateway = ModelGateway(
            cloudProvider = cloudProvider,
            localProvider = localProvider,
        )

        val result = gateway.generate(prompt = "hello", preferLocal = true, localHealthy = false)

        assertEquals("cloud:hello", result)
    }
}
