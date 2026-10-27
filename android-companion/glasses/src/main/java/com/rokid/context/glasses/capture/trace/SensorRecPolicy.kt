package com.rokid.context.glasses.capture.trace

import com.rokid.context.core.model.SENSOR_REC_PRESETS_HZ
import com.rokid.context.glasses.capture.session.SensorRatePolicy

/**
 * When the developer sensor recording may run (v0.9.7 B). It follows the SAME pause, park, heat and battery policy as capture and has no exception (the mark photo's exceptions belong to
 * the mark's own photo, not to a recording). Pure: the caller passes the facts the supervisor already holds. Returns the rate to record at (the recorder only ever sees what the classifier's feed delivers, so the effective rate is min(chosen, delivered)), 0 = not recording.
 */
object SensorRecPolicy {
    /** True in every state where the sensor rate policy lowers the IMU rate (rest, battery budget, phone in use, pause, not worn, parked, heat, battery): nothing is recorded then. Only reads the policy's reason set. */
    fun ratePolicyLowers(sessionReason: String?): Boolean = sessionReason != null && sessionReason in SensorRatePolicy.LOW_REASONS

    fun rateFor(hz: Int, sessionRunning: Boolean, captureAllowed: Boolean, parked: Boolean, heatStop: Boolean, lowBattery: Boolean, batterySaver: Boolean, sessionReason: String? = null): Int =
        if (hz in SENSOR_REC_PRESETS_HZ && sessionRunning && !ratePolicyLowers(sessionReason) && captureAllowed && !parked && !heatStop && !lowBattery && !batterySaver) hz else 0
}
