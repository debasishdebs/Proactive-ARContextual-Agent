package com.rokid.context.phone.nexus

import android.content.pm.PackageManager
import io.github.pca.nexusport.PackageSignerSource
import io.github.pca.nexusport.PcaSignerTrust
import io.github.pca.nexusport.SignerSource

/** Read-only presence checks (the manifest's `<queries>` make the three packages visible). Never installs, starts or opens anything. */
class NexusProbe(private val pm: PackageManager, private val ownPackage: String? = null, private val signers: SignerSource = PackageSignerSource(pm)) {
    private fun has(pkg: String): Boolean = try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }

    /** The bridge counts as installed only when it is signed like PCA (or pinned): a look-alike package is [NexusInstalled.bridgeUntrusted]. */
    fun installed(): NexusInstalled {
        val present = has(NexusPackages.BRIDGE)
        val trusted = present && ownPackage != null && PcaSignerTrust.trusted(signers, ownPackage, NexusPackages.BRIDGE)
        return NexusInstalled(bridge = trusted, hub = has(NexusPackages.HUB), rokidCoder = has(NexusPackages.ROKID_CODER), bridgeUntrusted = present && !trusted)
    }
}
