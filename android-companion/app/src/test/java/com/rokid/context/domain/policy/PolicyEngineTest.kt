package com.rokid.context.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {

    @Test
    fun batteryAbove30AllowsScheduledProcessing() {
        val decision = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 31,
                isCharging = false,
                batterySaverEnabled = false,
                requiresLocalModel = false,
                isManualRead = false,
            )
        )

        assertTrue(decision.canProcess)
    }

    @Test
    fun batteryAtOrBelow30RequiresCharging() {
        val at30 = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 30,
                isCharging = false,
                batterySaverEnabled = false,
                requiresLocalModel = false,
                isManualRead = false,
            )
        )
        val below30 = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 20,
                isCharging = false,
                batterySaverEnabled = false,
                requiresLocalModel = false,
                isManualRead = false,
            )
        )

        assertFalse(at30.canProcess)
        assertFalse(below30.canProcess)
    }

    @Test
    fun localModelRequiresChargingOrBatteryAbove70() {
        val blocked = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 70,
                isCharging = false,
                batterySaverEnabled = false,
                requiresLocalModel = true,
                isManualRead = false,
            )
        )
        val allowedByBattery = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 71,
                isCharging = false,
                batterySaverEnabled = false,
                requiresLocalModel = true,
                isManualRead = false,
            )
        )
        val allowedByCharging = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 10,
                isCharging = true,
                batterySaverEnabled = false,
                requiresLocalModel = true,
                isManualRead = false,
            )
        )

        assertFalse(blocked.canProcess)
        assertTrue(allowedByBattery.canProcess)
        assertTrue(allowedByCharging.canProcess)
    }

    @Test
    fun manualReadIsBlockedByBatterySaver() {
        val decision = PolicyEngine.decide(
            PolicyInput(
                batteryPercent = 90,
                isCharging = false,
                batterySaverEnabled = true,
                requiresLocalModel = false,
                isManualRead = true,
            )
        )

        assertFalse(decision.canProcess)
    }
}
