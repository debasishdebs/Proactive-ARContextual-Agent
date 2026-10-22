package com.rokid.context.capture.replay

/**
 * The fixed two-point battery line (S28, S80): `%/min = 0.433 + 0.0239 * camOpenS/min`. It passes through the A3 worn run (0.49 %/min at 2.4 s/min of camera-open time)
 * and the A2 point (0.6 %/min at 7 s/min). It is an ESTIMATE, never refitted in 3a. Hours from full are `100 / (%/min) / 60`.
 */
object BatteryLine {
    const val FLOOR_PCT_PER_MIN = 0.433
    const val SLOPE_PCT_PER_CAM_OPEN_S = 0.0239

    fun pctPerMin(camOpenSPerMin: Double): Double = FLOOR_PCT_PER_MIN + SLOPE_PCT_PER_CAM_OPEN_S * camOpenSPerMin

    /** Hours from a full battery at [pctPerMin] percent per minute. */
    fun hoursFromFull(pctPerMin: Double): Double = 100.0 / pctPerMin / 60.0

    fun hours(camOpenSPerMin: Double): Double = hoursFromFull(pctPerMin(camOpenSPerMin))
}
