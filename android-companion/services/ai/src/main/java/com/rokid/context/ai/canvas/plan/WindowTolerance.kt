package com.rokid.context.ai.canvas.plan

/**
 * The GLOBAL window tolerance (3d): the percent of a window term's seconds that must hold sensor data, written into every window term that names none at compile time.
 * The phone binds [provider] once at start to the stored `window_min_valid_pct` setting (default [ExprProgram.DEFAULT_MIN_VALID_PCT], 50..100); everything that compiles
 * (the canvas compile, the gated publish, replay candidates, try-it) reads it here, so a change reaches new compiles only: a published plan holds the number it was compiled with.
 */
object WindowTolerance {
    @Volatile var provider: () -> Int = { ExprProgram.DEFAULT_MIN_VALID_PCT }

    fun current(): Int = clean(provider())

    fun clean(pct: Int): Int = pct.coerceIn(com.rokid.context.core.model.context.graph.WindowRing.MIN_VALID_PCT, 100)
}
