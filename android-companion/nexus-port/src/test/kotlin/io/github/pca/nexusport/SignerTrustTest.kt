package io.github.pca.nexusport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Codex v2 r1 P1-3: who counts as "signed like PCA", with a fake PackageManager signer table. */
class SignerTrustTest {
    private val phone = "com.rokid.context"
    private val bridge = "io.github.pca.nexusbridge"
    private val DEBUG = "aa".repeat(32); private val REL = "bb".repeat(32); private val EVIL = "ee".repeat(32)
    private fun src(vararg e: Pair<String, Signing?>) = SignerSource { p -> e.toMap()[p] }

    @Test fun pinnedIsEmptyByDefault() = assertEquals(emptySet(), PcaSignerTrust.PINNED)

    @Test fun theSameSignerIsTrustedDebugAndRelease() {
        assertTrue(PcaSignerTrust.trusted(src(phone to Signing(setOf(DEBUG)), bridge to Signing(setOf(DEBUG))), phone, bridge))
        assertTrue(PcaSignerTrust.trusted(src(phone to Signing(setOf(REL)), bridge to Signing(setOf(REL))), phone, bridge))
    }

    @Test fun aDifferentSignerIsNotTrusted() {
        assertFalse(PcaSignerTrust.trusted(src(phone to Signing(setOf(DEBUG)), bridge to Signing(setOf(EVIL))), phone, bridge))
    }

    @Test fun notInstalledOrUnreadableOrEmptyIsNotTrusted() {
        assertFalse(PcaSignerTrust.trusted(src(phone to Signing(setOf(DEBUG))), phone, bridge))
        assertFalse(PcaSignerTrust.trusted(src(bridge to Signing(setOf(DEBUG))), phone, bridge))
        assertFalse(PcaSignerTrust.trusted(src(phone to Signing(setOf(DEBUG)), bridge to Signing(emptySet())), phone, bridge))
        assertFalse(PcaSignerTrust.trusted(SignerSource { throw IllegalStateException("pm") }, phone, bridge))
    }

    @Test fun aMultiSignerSetMustMatchExactly() {
        assertTrue(PcaSignerTrust.trusts(Signing(setOf(DEBUG, REL)), Signing(setOf(DEBUG, REL))))
        assertFalse(PcaSignerTrust.trusts(Signing(setOf(DEBUG, REL)), Signing(setOf(DEBUG, EVIL))))
        assertFalse(PcaSignerTrust.trusts(Signing(setOf(DEBUG, REL)), Signing(setOf(DEBUG))), "a subset is not enough")
    }

    @Test fun aKeyRotationLineageIsTrustedInEitherDirection() {
        // the phone app rotated debug -> release: the bridge still signed with the old key is in its lineage
        assertTrue(PcaSignerTrust.trusts(Signing(setOf(REL), setOf(DEBUG, REL)), Signing(setOf(DEBUG))))
        // the bridge rotated: PCA's current key is in the bridge's lineage
        assertTrue(PcaSignerTrust.trusts(Signing(setOf(DEBUG)), Signing(setOf(REL), setOf(DEBUG, REL))))
        assertFalse(PcaSignerTrust.trusts(Signing(setOf(REL), setOf(DEBUG, REL)), Signing(setOf(EVIL), setOf(EVIL))))
    }

    @Test fun aPinnedFingerprintIsTrustedOnlyWhenEveryCurrentCertificateIsPinned() {
        assertTrue(PcaSignerTrust.trusts(Signing(setOf(DEBUG)), Signing(setOf(REL)), pinned = setOf(REL)))
        assertTrue(PcaSignerTrust.trusts(null, Signing(setOf(REL)), pinned = setOf(REL)))
        assertFalse(PcaSignerTrust.trusts(Signing(setOf(DEBUG)), Signing(setOf(REL, EVIL)), pinned = setOf(REL)))
        assertFalse(PcaSignerTrust.trusts(Signing(setOf(DEBUG)), Signing(setOf(REL)), pinned = emptySet()), "empty pinned set trusts nothing extra")
        assertFalse(PcaSignerTrust.trusts(null, Signing(setOf(REL))))
    }
}
