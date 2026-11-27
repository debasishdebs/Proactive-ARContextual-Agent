package com.rokid.context.phone.place

import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.Skipped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B2 (owner D3): reference photos go over FREE Wi-Fi only, whatever the global setting; Offline only, the off switch and the data saver stop them; an unreadable state fails closed. */
class PhotoGateTest {
    private class Gate(var allowed: Boolean = true, var boom: Boolean = false) : DataGate {
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision { if (boom) error("x"); return if (allowed) DataDecision.ALLOW else DataDecision(false, "android_data_saver") }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {}
        override fun costlyNow() = false
    }
    private fun check(p: ModelPrivacy = ModelPrivacy.AUTO, c: NetworkCost = NetworkCost.FREE, g: DataGate = Gate(), on: Boolean = true) = PhotoGate.check({ p }, { c }, g, { on })

    @Test fun freeWifiAdmits() = assertNull(check())
    @Test fun everyCostlyOrUnknownNetworkWaitsEvenWhenTheSaverWouldAllow() {
        for (c in listOf(NetworkCost.HOME_MOBILE, NetworkCost.ROAMING, NetworkCost.UNKNOWN, NetworkCost.RESTRICTED)) assertEquals(c.name, SeedGate.NOT_FREE_WIFI, check(c = c))
        assertEquals(SeedGate.NO_NETWORK, check(c = NetworkCost.OFFLINE))
    }
    @Test fun offlineOnlyAndTheSwitchStopFirst() { assertEquals(SeedGate.OFFLINE_ONLY, check(p = ModelPrivacy.OFFLINE_ONLY)); assertEquals(PhotoGate.OFF, check(on = false)) }
    @Test fun theDataSaverStopsAndAnUnreadableStateFailsClosed() { assertEquals(SeedGate.DATA_SAVER, check(g = Gate(allowed = false))); assertEquals(SeedGate.UNREADABLE, check(g = Gate(boom = true))) }
}
