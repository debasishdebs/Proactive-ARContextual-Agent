package com.rokid.context.domain.policy

data class PolicyInput(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val batterySaverEnabled: Boolean,
    val requiresLocalModel: Boolean,
    val isManualRead: Boolean,
)

data class PolicyDecision(
    val canProcess: Boolean,
)

object PolicyEngine {
    fun decide(input: PolicyInput): PolicyDecision {
        if (input.batterySaverEnabled) {
            return PolicyDecision(canProcess = false)
        }

        if (input.requiresLocalModel) {
            return PolicyDecision(canProcess = input.isCharging || input.batteryPercent > 70)
        }

        if (input.batteryPercent <= 30 && !input.isCharging) {
            return PolicyDecision(canProcess = false)
        }

        return PolicyDecision(canProcess = true)
    }
}
