package com.rokid.context.glasses.capture.motion

import java.io.File

/** Size-bounded per-second feature CSV (`files/motion_features.csv`, rotated to `.1`). Numbers and enum names only; never throws. */
class MotionCsv(private val dir: File, private val maxBytes: Long = 4L * 1024 * 1024) {
    @Volatile var failures = 0
        private set

    @Synchronized fun append(row: String) {
        runCatching {
            require(SAFE.matches(row)) { "row is not numeric/enum-only" }
            dir.mkdirs()
            val f = File(dir, NAME)
            if (f.length() >= maxBytes) {
                val old = File(dir, "$NAME.1")
                old.delete()
                f.renameTo(old)
            }
            val fresh = !f.exists() || f.length() == 0L
            f.appendText(if (fresh) "$HEADER\n$row\n" else "$row\n")
        }.onFailure { failures++ }
    }

    companion object {
        private val SAFE = Regex("^[0-9.,A-Z_a-z-]+$")
        const val NAME = "motion_features.csv"
        const val HEADER = "t_ms,gyro_max,gyro_mean,gyro_rms,above_on_frac,acc_var,step_hz,rot_rad,glances,micro,state,model,fused,label,rate"
    }
}
