package com.rokid.context.ai.resolve

import com.rokid.context.ai.Http
import com.rokid.context.ai.OfflineOnlyDns
import com.rokid.context.ai.OfflineOnlyRefusedException
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.ModelPrivacy
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/** P2-1: a name that looks local must RESOLVE to the user's own network under Offline only; literals with leading zeros or non-ASCII digits are remote. */
class OfflineOnlyDnsTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private fun dnsOf(f: () -> List<InetAddress>) = object : Dns { override fun lookup(hostname: String): List<InetAddress> = f() }
    private fun fake(vararg addrs: String) = dnsOf { addrs.map(::ip) }
    private fun dns(mode: ModelPrivacy, vararg addrs: String) = OfflineOnlyDns({ mode }, fake(*addrs))
    private fun refused(block: () -> Unit) = runCatching { block() }.exceptionOrNull() is OfflineOnlyRefusedException

    @Test fun aLocalNameThatResolvesToAPublicAddressIsRefusedUnderOfflineOnly() {
        assertTrue(refused { dns(ModelPrivacy.OFFLINE_ONLY, "8.8.8.8").lookup("box.local") }) // a hijacked NXDOMAIN
        assertTrue(refused { dns(ModelPrivacy.OFFLINE_ONLY, "2001:4860:4860::8888").lookup("box.local") })
    }

    @Test fun oneOutsideAddressAmongTheAnswersIsEnoughToRefuse() {
        assertTrue(refused { dns(ModelPrivacy.OFFLINE_ONLY, "192.168.1.5", "93.184.216.34").lookup("box.local") })
    }

    @Test fun anEmptyAnswerIsRefusedAndAFailingResolverStaysAnUnknownHost() {
        assertTrue(refused { OfflineOnlyDns({ ModelPrivacy.OFFLINE_ONLY }, dnsOf { emptyList() }).lookup("x.local") })
        assertTrue(runCatching { OfflineOnlyDns({ ModelPrivacy.OFFLINE_ONLY }, dnsOf { throw UnknownHostException("x") }).lookup("x.local") }.exceptionOrNull() is UnknownHostException)
    }

    @Test fun ownNetworkAddressesPass() {
        for (a in listOf("127.0.0.1", "::1", "10.1.2.3", "172.16.0.9", "172.31.255.1", "192.168.1.5", "169.254.10.1", "fe80::1", "fd12:3456::1", "fc00::2"))
            assertEquals(a, listOf(ip(a)), dns(ModelPrivacy.OFFLINE_ONLY, a).lookup("h"))
    }

    @Test fun publicAndSpecialAddressesAreRefused() {
        for (a in listOf("1.1.1.1", "172.32.0.1", "172.15.0.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "2606:4700::1111", "2a00::1"))
            assertTrue(a, refused { dns(ModelPrivacy.OFFLINE_ONLY, a).lookup("h") })
    }

    @Test fun autoIsTheSystemResolverUnchanged() {
        assertEquals(listOf(ip("8.8.8.8")), dns(ModelPrivacy.AUTO, "8.8.8.8").lookup("example.com"))
    }

    @Test fun aModeReaderThatThrowsCountsAsOfflineOnly() {
        assertTrue(refused { OfflineOnlyDns({ error("prefs") }, fake("8.8.8.8")).lookup("example.com") })
    }

    @Test fun everyModelClientUsesTheSharedGuard() {
        assertSame(OfflineOnlyDns.shared, Http.modelClient().dns)
        assertSame(OfflineOnlyDns.shared, Http.modelClient(60).newBuilder().build().dns)
    }

    // ---- literal classification ----

    @Test fun ipv4LiteralsWithLeadingZerosOrNonAsciiDigitsAreRemote() {
        for (h in listOf("172.016.0.1", "010.0.0.1", "192.168.001.5", "10.0.0.01", "127.0.0.01", "١٠.٠.٠.١", "10.0.0.١", "０10.0.0.1", "10.0.0.1 ")) {
            // a trailing space is trimmed by the classifier, the rest must not be LAN
            if (h == "10.0.0.1 ") assertTrue(DataPolicy.isLanHost(h)) else assertFalse(h, DataPolicy.isLanHost(h))
        }
        for (h in listOf("10.0.0.1", "172.16.0.1", "192.168.1.5", "127.0.0.1", "169.254.1.1", "box.local", "localhost", "10.0.0.0"))
            assertTrue(h, DataPolicy.isLanHost(h))
        assertFalse(DataPolicy.isLanHost("0.0.0.0"))
    }
}
