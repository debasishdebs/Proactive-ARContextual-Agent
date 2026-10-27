package com.rokid.context.glasses.capture.trace

import com.rokid.context.core.model.SENSOR_REC_PRESETS_HZ

/**
 * When the developer sensor recording may run (v0.9.7 B). It follows the SAME pause, park, heat and battery policy as capture and has no exception (the mark photo's exceptions belong to
 * the mark's own photo, not to a recording). Pure: the caller passes the facts the supervisor already holds. Returns the rate to record at, 0 = not recording.
 */
object SensorRecPolicy {
    fun rateFor(hz: Int, sessionRunning: Boolean, captureAllowed: Boolean, parked: Boolean, heatStop: Boolean, lowBattery: Boolean, batterySaver: Boolean): Int =
        if (hz in SENSOR_REC_PRESETS_HZ && sessionRunning && captureAllowed && !parked && !heatStop && !lowBattery && !batterySaver) hz else 0
}
