package com.rokid.context.work.frames.place.replay

/**
 * Test-side what-ifs. Every fix F1-F4 is built into the real sampler, so no overlay changes the sampler any more. What is left is the second column of the report: [ALL] runs the same sampler with the
 * F5 text matcher where the stage is the oracle (a frame whose caption names a candidate is answered by the caption, else by the oracle).
 */
data class Overlays(val all: Boolean = false) {
    val label: String get() = if (all) "ALL" else "HEAD"

    companion object {
        val HEAD = Overlays()
        val ALL = Overlays(all = true)
    }
}
