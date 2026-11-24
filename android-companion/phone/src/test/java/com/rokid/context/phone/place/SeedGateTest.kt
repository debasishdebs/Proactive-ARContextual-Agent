package com.rokid.context.phone.place

import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.Skipped
import com.rokid.context.memory.place.SeedStopped
import com.rokid.context.phone.ui.settings.place.seedFailureText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seed's admission, asked before every cell and request: Offline only, free Wi-Fi and the BULK_OTHER data-saver decision, failing closed. */
class SeedGateTest {
    private class Gate(var allowed: Boolean = true, var boom: Boolean = false) : DataGate {
        val asked = mutableListOf<DataKind>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision { asked += kind; if (boom) error("x"); return if (allowed) DataDecision.ALLOW else DataDecision(false, "wifi_only") }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {}
        override fun costlyNow() = false
    }

    private fun check(p: ModelPrivacy = ModelPrivacy.AUTO, c: NetworkCost = NetworkCost.FREE, g: DataGate = Gate()) = SeedGate.check({ p }, { c }, g)

    @Test fun admittedOnAutoFreeWifiAndAnOpenSaver() { val g = Gate(); assertNull(check(g = g)); assertEquals(listOf(DataKind.BULK_OTHER), g.asked) }
    @Test fun offlineOnlyStops() = assertEquals(SeedGate.OFFLINE_ONLY, check(p = ModelPrivacy.OFFLINE_ONLY))
    @Test fun anythingButFreeWifiStops() { for (c in NetworkCost.entries.filter { it != NetworkCost.FREE }) assertEquals(c.name, SeedGate.NOT_FREE_WIFI, check(c = c)) }
    @Test fun theDataSaverBulkDecisionStops() = assertEquals(SeedGate.DATA_SAVER, check(g = Gate(allowed = false)))
    @Test fun anUnreadableStateFailsClosed() {
        assertEquals(SeedGate.UNREADABLE, check(g = Gate(boom = true)))
        assertEquals(SeedGate.UNREADABLE, SeedGate.check({ error("x") }, { NetworkCost.FREE }, Gate()))
        assertEquals(SeedGate.UNREADABLE, SeedGate.check({ ModelPrivacy.AUTO }, { error("x") }, Gate()))
    }

    @Test fun aStoppedSeedIsReportedAsStoppedWithItsReason() {
        val text = seedFailureText(SeedStopped(SeedGate.NOT_FREE_WIFI))
        assertTrue(text, text.startsWith("Seeding stopped: not on free Wi-Fi."))
    }
}
