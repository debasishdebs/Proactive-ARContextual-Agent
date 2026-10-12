package com.rokid.context.domain.policy

data class PolicyInput(
    val batteryPercent: Int,
    val isCharging: Boolean,
    val batterySaverEnabled: Boolean,
    val requiresLocalModel: Boolean,
    val isManualRead: Boolean,
)

data class PolicyDecision(
    val canProcessNow: Boolean,
    val canRunLocalModel: Boolean,
    val canManualReadNow: Boolean,
)

object PolicyEngine {
    fun decide(input: PolicyInput): PolicyDecision {
        val canRunLocalModel = input.isCharging || input.batteryPercent > 70
        val canScheduleNow = input.isCharging || input.batteryPercent > 30
        val canManualReadNow = input.isManualRead && !input.batterySaverEnabled
        val canProcessNow = canScheduleNow || canManualReadNow

        return PolicyDecision(
            canProcessNow = canProcessNow,
            canRunLocalModel = canRunLocalModel,
            canManualReadNow = canManualReadNow,
        )
    }
}
