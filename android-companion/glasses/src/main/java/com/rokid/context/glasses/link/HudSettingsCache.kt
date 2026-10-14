package com.rokid.context.glasses.link

import android.content.SharedPreferences
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.normalized

/** The capture settings the glasses act on, from the last phone push (snapped to their allowed sets; Fixed when never pushed). */
data class CaptureSettings(
    val captureMode: CaptureMode,
    val quickLookMs: Int,
    val workMinutes: Int,
    val restMinutes: Int,
    val pauseWhenNotWorn: Boolean,
    val captureIntervalSec: Int,
)

/** Last `pc_settings`, kept across restarts: verbosity, gestures and cadence apply offline (spec §5.5). */
class HudSettingsCache(private val prefs: SharedPreferences) {
    /** The last value read or written, so per-capture checks (quiet hours) don't decode the prefs each time. */
    @Volatile private var last: HudSettings? = null

    fun read(): HudSettings = last
        ?: validQuiet(prefs.getString(KEY, null)?.let { (Wire.decode<HudSettings>(it) as? Decoded.Ok)?.value } ?: HudSettings()).normalized().also { last = it }

    /** [read] with the keyframe-capture values normalized (nearest allowed value, R >= 15 when W > 45). */
    fun readNormalized(): HudSettings = read().normalized()

    fun capture(): CaptureSettings = readNormalized().let {
        CaptureSettings(it.captureMode, it.quickLookMs, it.workMinutes, it.restMinutes, it.pauseWhenNotWorn, it.captureIntervalSec)
    }

    fun write(s: HudSettings) {
        val v = validQuiet(s).normalized() // the glasses never run on raw wire values
        last = v
        prefs.edit().putString(KEY, Wire.encode(v)).apply()
    }

    companion object {
        const val PREFS = "hud"
        private const val KEY = "hudSettings"

        /** Quiet hours with a minute outside 0..1439 are dropped (no window), never stored or evaluated. */
        fun validQuiet(s: HudSettings): HudSettings =
            s.quietHours?.takeUnless { it.startMinute in 0..1439 && it.endMinute in 0..1439 }?.let { s.copy(quietHours = null) } ?: s
    }
}
