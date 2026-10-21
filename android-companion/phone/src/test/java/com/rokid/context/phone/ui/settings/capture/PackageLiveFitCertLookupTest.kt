package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.context.WatchMotionIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSigningInfo
import java.security.MessageDigest

/** The production lookup over a fake signing info: the real digest, or the refusal, never a stand-in. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class PackageLiveFitCertLookupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val certA = byteArrayOf(1, 2, 3, 4)
    private val certB = byteArrayOf(9, 8, 7, 6, 5)
    private fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun install(signatures: List<ByteArray>, history: List<ByteArray>? = null) {
        val si = SigningInfo::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val sh = shadowOf(si) as ShadowSigningInfo
        sh.setSignatures(signatures.map { Signature(it) }.toTypedArray())
        history?.let { h -> sh.setPastSigningCertificates(h.map { Signature(it) }.toTypedArray()) }
        val info = PackageInfo().apply { packageName = WatchMotionIntake.LIVEFIT_PACKAGE; signingInfo = si }
        shadowOf(ctx.packageManager).installPackage(info)
    }

    @Test fun notInstalledGivesNoDigestAndCanBePinnedLater() {
        val l = PackageLiveFitCertLookup(ctx.packageManager)
        assertNull(l.sha256()); assertFalse(l.cannotPin())
    }

    @Test fun singleSignerGivesItsLowercaseHexSha256() {
        install(listOf(certA))
        val l = PackageLiveFitCertLookup(ctx.packageManager)
        assertEquals(hex(certA), l.sha256())
        assertEquals(64, l.sha256()!!.length); assertTrue(l.sha256()!!.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(l.cannotPin())
    }

    @Test fun multipleSignersAreRefusedAndCannotBePinned() {
        install(listOf(certA, certB))
        val l = PackageLiveFitCertLookup(ctx.packageManager)
        assertNull(l.sha256()); assertTrue(l.cannotPin())
    }

    @Test fun signingHistoryUsesTheLastEntryTheCurrentCertificate() {
        install(signatures = listOf(certB), history = listOf(certA, certB))
        val l = PackageLiveFitCertLookup(ctx.packageManager)
        assertEquals(hex(certB), l.sha256())
        assertFalse(l.cannotPin())
    }
}
