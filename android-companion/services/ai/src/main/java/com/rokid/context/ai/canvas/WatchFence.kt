package com.rokid.context.ai.canvas

import java.util.concurrent.atomic.AtomicLong

/**
 * GAP-2: the in-process fence of the watch values. A watch run captures [generation] before it reads the hub's values; the source switch-off and Delete all [bump] it, and
 * `CanvasDao.insertRunItemsFenced` refuses (inside its transaction) any write of `watch:<atMs>` rows made under an older value, so a run that was executing when the
 * values were forgotten cannot put them back. Memory only on purpose: the values it guards are memory only, and a restart ends every run (no schema).
 */
object WatchFence {
    val generation = AtomicLong(0)
    fun bump() { generation.incrementAndGet() }
}
