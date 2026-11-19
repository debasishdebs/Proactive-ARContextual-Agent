package com.rokid.context.work

import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.ModelPrivacyPolicy
import com.rokid.context.core.net.Skipped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-4: the background work gate asks with the REAL endpoint reach, so an on-device or LAN vision endpoint keeps night describe and the suspect sweep running under Offline only. */
class DataSaverWorkGateReachTest {
    private class Gate(val mode: ModelPrivacy) : DataGate {
        val asked = mutableListOf<EndpointReach>(); var skipped = 0
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision { asked += reach; return if (ModelPrivacyPolicy.allows(mode, reach)) DataDecision.ALLOW else DataDecision(false, "offline_only") }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skipped++ }
        override fun costlyNow() = false
    }

    @Test fun underOfflineOnlyARemoteEndpointClosesTheGateAndALocalOneOpensIt() {
        val g = Gate(ModelPrivacy.OFFLINE_ONLY)
        assertFalse(DataSaverWorkGate.open(g, EndpointReach.REMOTE)); assertFalse(DataSaverWorkGate.open(g))
        assertTrue(DataSaverWorkGate.open(g, EndpointReach.LAN)); assertTrue(DataSaverWorkGate.open(g, EndpointReach.DEVICE))
        assertTrue(DataSaverWorkGate.atStart(g, EndpointReach.DEVICE)); assertEquals(0, g.skipped)
        assertFalse(DataSaverWorkGate.atStart(g, EndpointReach.REMOTE)); assertEquals(1, g.skipped)
    }

    @Test fun theReachIsPassedThroughToTheGate() {
        val g = Gate(ModelPrivacy.AUTO)
        DataSaverWorkGate.open(g, EndpointReach.LAN)
        assertEquals(listOf(EndpointReach.LAN), g.asked)
    }
}
