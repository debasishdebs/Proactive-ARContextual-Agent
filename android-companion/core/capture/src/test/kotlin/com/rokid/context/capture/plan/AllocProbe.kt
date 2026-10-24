package com.rokid.context.capture.plan

import java.lang.management.ManagementFactory

/** Thread allocation counter for the no-allocation tests (HotSpot's per-thread allocated bytes); same probe as core:model's tests. */
object AllocProbe {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    fun bytes(block: () -> Unit): Long {
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        block()
        return mx.getThreadAllocatedBytes(id) - before
    }
}
