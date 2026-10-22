package com.rokid.context.glasses.capture.golden

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.session.BudgetTier
import com.rokid.context.glasses.capture.session.HeatReading
import kotlin.random.Random

/** A scripted head turn: rotation [rate] rad/s about the vertical axis for [durMs] from [atMs]; [scene] is shown from its start. */
class Turn(val atMs: Long, val durMs: Long, val rate: Float, val scene: Int?)

/** Everything a script feeds the real session. All times are virtual ms from session start. */
class ScriptPlan {
    val turns = ArrayList<Turn>()
    val walks = ArrayList<LongRange>()
    val ctx = ArrayList<Pair<Long, ContextState>>()
    val heat = ArrayList<Pair<Long, HeatReading>>()
    val failOpens = ArrayList<Pair<Long, Int>>()
    val busy = ArrayList<Pair<Long, Boolean>>()
    val captureNows = ArrayList<Long>()
}

/** A seeded script. [plan] must be a pure function of its [Random]. */
class GoldenScript(
    val name: String,
    val seed: Long,
    val durationMs: Long,
    val settings: HudSettings,
    val tier: BudgetTier,
    val workMin: Int = 90,
    val restMin: Int = 15,
    val plan: (Random, ScriptPlan, Long) -> Unit,
)

object GoldenScripts {
    private const val MIN = 60_000L

    /** Glances (a turn of 0.4-1.2 s at 1-2 rad/s, usually onto a new scene) every [gapMin]..[gapMax] ms between [from] and [to]. */
    private fun glances(r: Random, p: ScriptPlan, from: Long, to: Long, gapMin: Long, gapMax: Long, newSceneOdds: Int = 80) {
        var t = from
        while (t < to) {
            val scene = if (r.nextInt(100) < newSceneOdds) 1 + r.nextInt(5000) else null
            p.turns += Turn(t, 400L + r.nextInt(800), 1.0f + r.nextFloat(), scene)
            t += gapMin + (r.nextLong(gapMax - gapMin + 1))
        }
    }

    private fun walkSpell(r: Random, p: ScriptPlan, from: Long, lenMs: Long) {
        p.walks += from until from + lenMs
        glances(r, p, from + 10_000, from + lenMs, 12_000, 40_000, 60)
    }

    val unknownWalk = GoldenScript(
        "unknown_walk_20min", 11L, 20 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.MOTION), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        var t = 5_000L
        while (t < d) {
            val len = 60_000L + r.nextLong(90_000)
            if (r.nextBoolean()) { walkSpell(r, p, t, minOf(len, d - t)) }
            else glances(r, p, t, minOf(t + len, d), 15_000, 60_000)
            t += len + 10_000
        }
    }

    val restStill = GoldenScript(
        "rest_still_30min", 22L, 30 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.REST
        glances(r, p, 20_000, d, 40_000, 120_000, 50)
    }

    val flightSettings = GoldenScript(
        "flight_settings_20min", 33L, 20 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        p.ctx += 3 * MIN to ContextState.FLIGHT
        p.ctx += 17 * MIN to ContextState.UNKNOWN
        glances(r, p, 10_000, d, 30_000, 90_000, 60)
    }

    val workRestRest = GoldenScript(
        "workrest_rest_30min", 44L, 30 * MIN,
        HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE, workRestEnabled = true), BudgetTier.T0,
        workMin = 5, restMin = 10,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        glances(r, p, 10_000, d, 20_000, 60_000, 70)
    }

    val budgetT2 = GoldenScript(
        "budget_t2_20min", 55L, 20 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE), BudgetTier.T2,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        glances(r, p, 8_000, 8 * MIN, 12_000, 45_000, 70)
        walkSpell(r, p, 8 * MIN + 5_000, 90_000)
        glances(r, p, 11 * MIN, d, 20_000, 90_000, 70)
    }

    val heatSlow = GoldenScript(
        "heat_slow_10min", 66L, 10 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        p.heat += 0L to HeatReading(0, Float.NaN, 30f)
        p.heat += 60_000L to HeatReading(0, Float.NaN, 41f)
        p.heat += 8 * MIN to HeatReading(0, Float.NaN, 30f)
        glances(r, p, 5_000, d, 15_000, 50_000, 70)
    }

    val bindBackoff = GoldenScript(
        "bind_backoff_10min", 77L, 10 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.SETTLE), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        p.failOpens += 0L to 1            // the cold-start grace
        p.failOpens += 2 * MIN to 2       // two real bind failures (never the 3 that latch the Fixed fallback)
        p.failOpens += 5 * MIN to 2
        p.busy += 7 * MIN to true         // another app holds the camera
        p.busy += 7 * MIN + 70_000L to false
        glances(r, p, 5_000, d, 15_000, 50_000, 80)
    }

    val captureNowMix = GoldenScript(
        "capture_now_mix_10min", 88L, 10 * MIN, HudSettings(captureMode = CaptureMode.SMART, cameraWake = CameraWake.MOTION), BudgetTier.T0,
    ) { r, p, d ->
        p.ctx += 0L to ContextState.UNKNOWN
        glances(r, p, 5_000, 4 * MIN, 15_000, 45_000, 70)
        walkSpell(r, p, 4 * MIN + 5_000, 100_000)
        glances(r, p, 6 * MIN + 20_000, d, 20_000, 60_000, 70)
        for (at in listOf(20_000L, 21_500L, 95_000L, 150_000L, 4 * MIN + 40_000, 5 * MIN, 8 * MIN + 3_000)) p.captureNows += at
    }

    val all = listOf(unknownWalk, restStill, flightSettings, workRestRest, budgetT2, heatSlow, bindBackoff, captureNowMix)
}
