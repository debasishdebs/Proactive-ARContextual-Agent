package io.github.pca.nexusport

import android.content.pm.PackageManager
import java.security.MessageDigest

/** The signing certificates of one installed package, as lowercase hex SHA-256 digests. [current] = the certificates its APK is signed with now; [history] = the rotation lineage (equal to [current] when it never rotated). */
data class Signing(val current: Set<String>, val history: Set<String> = current)

/** Where signing certificates come from (the real PackageManager in production, a fake in tests). Null = not installed or unreadable: never trusted. */
fun interface SignerSource {
    fun signing(pkg: String): Signing?
}

/**
 * Who PCA trusts to be "its own" bridge APK (Codex v2 r1 P1-3): the phone app binds an explicit package, and Android lets ANY installed app claim that package name when the genuine bridge is absent, so the
 * phone accepts a binder only from a package signed like itself. Debug builds share the debug key, so "signed like the phone app" works for PCA debug + bridge debug; a release key lineage works through
 * key rotation (the bridge's current certificate is in the phone's lineage, or the other way round).
 *
 * Trusted when, and only when, the bridge is installed with at least one certificate and one of:
 *  1. its current certificates equal the phone app's (the normal case);
 *  2. both have exactly one current certificate and one is in the other's rotation lineage;
 *  3. every current certificate is in [pinned] (EMPTY by default: a release can pin its bridge certificate fingerprints here, SHA-256 lowercase hex, without changing any logic).
 * Anything else, an unreadable certificate or a throw, is "not trusted" (fail closed).
 */
object PcaSignerTrust {
    /** Pinned bridge certificate fingerprints (SHA-256, lowercase hex). EMPTY on purpose: the default trust is "same signer as the phone app". Add a release fingerprint here to trust a bridge built with another key. */
    val PINNED: Set<String> = emptySet()

    fun trusts(own: Signing?, other: Signing?, pinned: Set<String> = PINNED): Boolean {
        if (other == null || other.current.isEmpty()) return false
        if (pinned.isNotEmpty() && pinned.containsAll(other.current)) return true
        if (own == null || own.current.isEmpty()) return false
        if (other.current == own.current) return true
        if (other.current.size == 1 && own.current.size == 1) {
            if (other.current.single() in own.history || own.current.single() in other.history) return true
        }
        return false
    }

    /** True when [pkg] is signed like [ownPkg] (or pinned). Never throws. */
    fun trusted(source: SignerSource, ownPkg: String, pkg: String, pinned: Set<String> = PINNED): Boolean =
        try { trusts(source.signing(ownPkg), source.signing(pkg), pinned) } catch (_: Exception) { false }
}

/** [SignerSource] over the real PackageManager (API 28+ signing info; this project's minSdk is 29). Needs the package to be visible (the phone's `<queries>` list the bridge). */
class PackageSignerSource(private val pm: PackageManager) : SignerSource {
    @Suppress("DEPRECATION")
    override fun signing(pkg: String): Signing? = try {
        val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        if (si == null) null
        else {
            val cur = si.apkContentsSigners.map { sha256(it.toByteArray()) }.toSet()
            val hist = if (si.hasMultipleSigners()) cur else (si.signingCertificateHistory?.map { sha256(it.toByteArray()) }?.toSet() ?: cur)
            if (cur.isEmpty()) null else Signing(cur, hist + cur)
        }
    } catch (_: Exception) { null }

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
