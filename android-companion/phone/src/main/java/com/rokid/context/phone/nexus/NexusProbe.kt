package com.rokid.context.phone.nexus

import android.content.pm.PackageManager

/** Read-only presence checks (the manifest's `<queries>` make the three packages visible). Never installs, starts or opens anything. */
class NexusProbe(private val pm: PackageManager) {
    private fun has(pkg: String): Boolean = try { pm.getPackageInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }

    fun installed(): NexusInstalled = NexusInstalled(bridge = has(NexusPackages.BRIDGE), hub = has(NexusPackages.HUB), rokidCoder = has(NexusPackages.ROKID_CODER))
}
