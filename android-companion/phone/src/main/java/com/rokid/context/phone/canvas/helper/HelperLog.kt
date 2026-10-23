package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.core.log.Level

/**
 * The one way the 3b phone classes write a line (always-on rule, core plan section 12): through the gated core port (`PhoneLogging.canvasPort()` in production,
 * a recording port in tests), never the platform logger. A line is a constant template of lower-case words plus at most one count: no ids, tokens, codes,
 * names, hashes or payload fields ever go in. Never throws into the caller.
 */
internal fun CanvasLogSink.helperLine(msg: String) {
    try { log(Level.I, "canvas", msg) } catch (_: Throwable) { }
}
