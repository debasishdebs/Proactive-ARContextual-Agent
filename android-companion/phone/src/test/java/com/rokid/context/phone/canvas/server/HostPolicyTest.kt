package com.rokid.context.phone.canvas.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPolicyTest {
    private var lan: String? = null
    private var secure = false
    private val policy = HostPolicy(port = { 8765 }, lanIp = { lan }, secure = { secure })

    @Test fun loopbackHostsAllowed() {
        assertTrue(policy.hostAllowed("127.0.0.1:8765"))
        assertTrue(policy.hostAllowed("localhost:8765"))
        assertTrue(policy.hostAllowed("LOCALHOST:8765"))
    }

    @Test fun lanHostOnlyWhenOn() {
        assertFalse(policy.hostAllowed("192.168.1.20:8765"))
        lan = "192.168.1.20"
        assertTrue(policy.hostAllowed("192.168.1.20:8765"))
        assertFalse(policy.hostAllowed("192.168.1.21:8765"))
        lan = null
        assertFalse(policy.hostAllowed("192.168.1.20:8765"))
    }

    @Test fun rebindHostRejected() {
        lan = "192.168.1.20"
        for (h in listOf(null, "", "evil.example:8765", "evil.example", "127.0.0.1", "localhost",
            "127.0.0.1:8766", "127.0.0.1:8765.evil.example", "127.0.0.1:8765, evil.example", "[::1]:8765", "0.0.0.0:8765")) {
            assertFalse("host=$h", policy.hostAllowed(h))
        }
    }

    @Test fun portFollowsTheBoundPort() {
        var port = 8765
        val p = HostPolicy({ port }, { null })
        port = 8767
        assertTrue(p.hostAllowed("127.0.0.1:8767"))
        assertFalse(p.hostAllowed("127.0.0.1:8765"))
    }

    @Test fun originMustMatchHostForPost() {
        val host = "127.0.0.1:8765"
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) {
            assertTrue(policy.originAllowed(m, "http://127.0.0.1:8765", host))
            assertFalse(policy.originAllowed(m, "http://evil.example", host))
            assertFalse(policy.originAllowed(m, "https://127.0.0.1:8765", host))
            assertFalse(policy.originAllowed(m, "null", host))
            assertFalse(policy.originAllowed(m, "http://localhost:8765", host))
        }
        assertTrue(policy.originAllowed("GET", "http://evil.example", host))
        assertTrue(policy.originAllowed("HEAD", "http://evil.example", host))
    }

    @Test fun missingOriginAllowed() {
        assertTrue(policy.originAllowed("POST", null, "127.0.0.1:8765"))
        assertTrue(policy.originAllowed("DELETE", null, "127.0.0.1:8765"))
    }

    @Test fun httpsOriginWhenSecure() {
        secure = true
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) {
            assertTrue(policy.originAllowed(m, "https://127.0.0.1:8765", "127.0.0.1:8765"))
            assertTrue(policy.originAllowed(m, "https://192.168.1.20:8765", "192.168.1.20:8765"))
            assertTrue(policy.originAllowed(m, "https://localhost:8765", "localhost:8765"))
            // a different host or port in the Origin, or a case trick, is still refused
            assertFalse(policy.originAllowed(m, "https://192.168.1.21:8765", "192.168.1.20:8765"))
            assertFalse(policy.originAllowed(m, "https://127.0.0.1:8766", "127.0.0.1:8765"))
            assertFalse(policy.originAllowed(m, "HTTPS://127.0.0.1:8765", "127.0.0.1:8765"))
            assertFalse(policy.originAllowed(m, "https://evil.example", "127.0.0.1:8765"))
            assertFalse(policy.originAllowed(m, "null", "127.0.0.1:8765"))
        }
        assertTrue(policy.originAllowed("POST", null, "127.0.0.1:8765"))
        assertFalse(policy.originAllowed("POST", "https://127.0.0.1:8765", null))
        assertTrue(policy.originAllowed("GET", "https://evil.example", "127.0.0.1:8765"))
    }

    @Test fun httpOriginRejectedWhenSecure() {
        secure = true
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) {
            assertFalse(policy.originAllowed(m, "http://127.0.0.1:8765", "127.0.0.1:8765"))
            assertFalse(policy.originAllowed(m, "http://192.168.1.20:8765", "192.168.1.20:8765"))
        }
    }

    @Test fun secureFlagIsReadPerCall() {
        assertTrue(policy.originAllowed("POST", "http://127.0.0.1:8765", "127.0.0.1:8765"))
        secure = true
        assertFalse(policy.originAllowed("POST", "http://127.0.0.1:8765", "127.0.0.1:8765"))
        secure = false
        assertFalse(policy.originAllowed("POST", "https://127.0.0.1:8765", "127.0.0.1:8765"))
    }

    @Test fun hostPolicyUnchangedByScheme() {
        // DNS-rebinding protection is the Host check; it must work the same over https, with LAN IPs and loopback
        secure = true
        lan = "192.168.1.20"
        assertTrue(policy.hostAllowed("192.168.1.20:8765"))
        assertTrue(policy.hostAllowed("127.0.0.1:8765"))
        assertTrue(policy.hostAllowed("localhost:8765"))
        for (h in listOf(null, "", "evil.example:8765", "192.168.1.20", "192.168.1.21:8765", "192.168.1.20:8765.evil.example", "[::1]:8765")) {
            assertFalse("host=$h", policy.hostAllowed(h))
        }
    }
}
