package com.rokid.context.canvas

import com.rokid.context.core.model.context.WatchSample

/** The recent watch values the phone's context hub accepted between two instants (inclusive), in arrival order; empty when the watch source is off or nothing arrived. Read only. */
fun interface WatchRunSource { suspend fun recent(fromMs: Long, toMs: Long): List<WatchSample> }
