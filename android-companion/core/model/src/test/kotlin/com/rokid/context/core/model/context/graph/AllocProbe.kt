package com.rokid.context.core.model.context.graph

import java.lang.management.ManagementFactory

/** Thread allocation counter for the no-allocation tests (HotSpot's per-thread allocated bytes). */
object AllocProbe {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    fun bytes(block: () -> Unit): Long {
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        block()
        return mx.getThreadAllocatedBytes(id) - before
    }
}
