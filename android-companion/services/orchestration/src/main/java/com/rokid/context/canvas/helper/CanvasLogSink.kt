package com.rokid.context.canvas.helper

import com.rokid.context.core.log.LogPort

/**
 * The call-side logger type of every 3b class in `:services:orchestration` (one logger, one gate, always-on LGC1). It is the merged [LogPort], not a second
 * interface; production binds `PcLog.port("canvas")`, tests bind a recording port. Never a concrete sink, and no `android.util.Log` in a 3b file.
 */
typealias CanvasLogSink = LogPort
