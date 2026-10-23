package com.rokid.context.canvas.helper

/** The shared port contract against the WP1 fake. */
class InMemoryPortContractTest : HelperJobPortContract() {
    override fun newHarness(): PortHarness = FakePortHarness()
}
