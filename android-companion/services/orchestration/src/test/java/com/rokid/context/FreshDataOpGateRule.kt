package com.rokid.context

import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.orchestration.DataOpGate
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * The process-global [DataOpGate.shared] and its lease registry outlive a test: a Forget or Delete all a test started on another thread (or a background worker it woke) can still hold
 * or wait on the exclusive side when the test ends, and every later export, lease or save in the same JVM then fails with "busy: a delete is running". This rule gives each test a fresh
 * gate and an empty lease registry before it runs and again after it, so one test's leftovers cannot cascade into the next (test isolation only; production never swaps the gate).
 */
class FreshDataOpGateRule : TestWatcher() {
    private fun reset() { Leases.shared.revokeAll(); DataOpGate.shared = DataOpGate() }
    override fun starting(description: Description) { reset() }
    override fun finished(description: Description) { reset() }
}
