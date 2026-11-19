package com.rokid.context.ai.datasaver

import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.NetworkCosts
import com.rokid.context.core.net.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure mapping from capability bits to a snapshot (the VPN rule); the callback plumbing needs a device. */
class NetworkCostMonitorTest {
    private fun caps(wifi: Boolean = false, cell: Boolean = false, vpn: Boolean = false, validated: Boolean = true, notMetered: Boolean = false, notRoaming: Boolean = true) =
        RawCaps(wifi, false, cell, vpn, validated, notMetered, notRoaming)

    private fun cost(s: NetSnapshot, abroad: Boolean = false) = NetworkCosts.classify(s, abroad)

    @Test fun plainWifiAndMobile() {
        val wifi = caps(wifi = true, notMetered = true)
        assertEquals(NetworkCost.FREE, cost(NetworkCostMonitor.snapshotOf(wifi, wifi, false, false)))
        val mob = caps(cell = true)
        assertEquals(NetworkCost.HOME_MOBILE, cost(NetworkCostMonitor.snapshotOf(mob, mob, false, false)))
        assertEquals(NetworkCost.ROAMING, cost(NetworkCostMonitor.snapshotOf(mob, mob, true, false)))
    }

    @Test fun aVpnOverWifiThatReportsMeteredIsFreeOnlyWhenTheRealWifiIsUnmetered() {
        val vpnMetered = caps(wifi = true, vpn = true, notMetered = false)
        // the real Wi-Fi underneath is unmetered and validated: free
        val realWifi = caps(wifi = true, notMetered = true)
        assertEquals(NetworkCost.FREE, cost(NetworkCostMonitor.snapshotOf(realWifi, vpnMetered, false, false)))
        // the real Wi-Fi is itself metered: not free
        assertEquals(NetworkCost.HOME_MOBILE, cost(NetworkCostMonitor.snapshotOf(realWifi.copy(notMetered = false), vpnMetered, false, false)))
        // no real Wi-Fi found: the VPN's own metered reading stands (the safe direction)
        assertEquals(NetworkCost.HOME_MOBILE, cost(NetworkCostMonitor.snapshotOf(vpnMetered, vpnMetered, false, false)))
    }

    @Test fun aVpnThatNamesNoTransportIsUnknown() {
        val bare = caps(vpn = true)
        assertEquals(Transport.OTHER, NetworkCostMonitor.snapshotOf(bare, bare, false, false).transport)
        assertEquals(NetworkCost.UNKNOWN, cost(NetworkCostMonitor.snapshotOf(bare, bare, false, false)))
    }

    @Test fun aVpnOverMobileKeepsTheMobileRules() {
        val vpnCell = caps(cell = true, vpn = true)
        assertEquals(NetworkCost.HOME_MOBILE, cost(NetworkCostMonitor.snapshotOf(vpnCell, vpnCell, false, false)))
        assertEquals(NetworkCost.ROAMING, cost(NetworkCostMonitor.snapshotOf(vpnCell, vpnCell, false, false), abroad = true))
    }
}
