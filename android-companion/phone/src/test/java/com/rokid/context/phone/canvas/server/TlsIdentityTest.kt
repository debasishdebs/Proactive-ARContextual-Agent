package com.rokid.context.phone.canvas.server

import android.app.Application
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TlsIdentityTest {
    private val source = File("src/main/java/com/rokid/context/phone/canvas/server/TlsIdentity.kt").readText()

    @Test fun fingerprintFormat() {
        val id = PkcsTlsIdentity.load()
        val groups = id.fingerprint.split(' ')
        assertEquals(16, groups.size)
        assertTrue(id.fingerprint, groups.all { Regex("[0-9A-F]{4}").matches(it) })
        // it is the SHA-256 of the DER certificate, nothing else
        val hex = MessageDigest.getInstance("SHA-256").digest(id.certificate.encoded).joinToString("") { "%02X".format(it) }
        assertEquals(hex.chunked(4).joinToString(" "), id.fingerprint)
        assertEquals("0001 0203 0405 0607 0809 0A0B 0C0D 0E0F 1011 1213 1415 1617 1819 1A1B 1C1D 1E1F", TlsFingerprint.format(ByteArray(32) { it.toByte() }))
    }

    @Test fun matchesOwnCertOnly() {
        val ours = PkcsTlsIdentity.load()
        assertTrue(ours.matches(ours.certificate))
        assertFalse(ours.matches(PkcsTlsIdentity.other().certificate))
        assertTrue(PkcsTlsIdentity.other().matches(PkcsTlsIdentity.other().certificate))
    }

    @Test fun constantTimeCompareUsed() {
        assertTrue(source.contains("MessageDigest.isEqual"))
        // no ordinary comparison of digests or fingerprint strings
        assertFalse(source.contains("contentEquals"))
        assertFalse(Regex("fingerprint\\s*==").containsMatchIn(source))
        assertFalse(Regex("digest\\s*==").containsMatchIn(source))
    }

    @Test fun digestsIncludeNone() {
        val spec = KeystoreTlsIdentity.keySpec("pc_test_alias", 1_700_000_000_000L)
        val d = spec.digests.toSet()
        assertTrue(d.containsAll(setOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)))
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals("pc_test_alias", spec.keystoreAlias)
        assertEquals("CN=Proactive Context canvas", spec.certificateSubject.name)
        assertEquals(1_700_000_000_000L - 86_400_000L, spec.certificateNotBefore!!.time)
        assertEquals(1_700_000_000_000L + 825L * 86_400_000L, spec.certificateNotAfter!!.time)
        assertFalse("TLS must not wait for a user", spec.isUserAuthenticationRequired)
        assertEquals(KeystoreTlsIdentity.ALIAS, "pc_canvas_tls")
    }

    @Test fun noKeyMaterialExportedOrLogged() {
        for (bad in listOf("getEncoded", ".encoded", "Log.", "println", "printStackTrace", "Base64", "PEM")) {
            // the certificate's own DER is hashed in TlsFingerprint; the key is never serialised
            if (bad == "getEncoded" || bad == ".encoded") {
                val keyLines = source.lines().filter { it.contains(bad) && it.contains("key", ignoreCase = true) && !it.contains("cert", ignoreCase = true) }
                assertTrue("$bad on a key line: $keyLines", keyLines.isEmpty())
            } else assertFalse("$bad in TlsIdentity.kt", source.contains(bad))
        }
    }

    @Test fun sslDecisionProceedsOnlyOnMatch() {
        assertTrue(SslDecision.decide(true))
        assertFalse(SslDecision.decide(false))
    }
}
