package com.rokid.context.phone.nexus

import io.github.pca.nexusport.NexusResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0 T8: every state of the Rokid Nexus screen has its own plain text, and the mapping from what PCA can observe. */
class NexusStatusTest {
    private val all = NexusInstalled(bridge = true, hub = true, rokidCoder = false)
    private fun nexus(i: NexusInstalled = all, bound: Boolean = true, code: Int? = null, refused: Boolean = false, pcaLinkUp: Boolean = false) =
        NexusInputs(i, hostIsNexus = true, bindRefused = refused, bound = bound, lastCode = code, pcaLinkUp = pcaLinkUp)

    @Test fun notInstalledStatesComeFirstWhateverTheHost() {
        assertEquals(NexusState.BRIDGE_NOT_INSTALLED, NexusStatus.of(NexusInputs(NexusInstalled(false, true, false), hostIsNexus = false)))
        assertEquals(NexusState.BRIDGE_NOT_INSTALLED, NexusStatus.of(nexus(NexusInstalled(false, false, true))))
        assertEquals(NexusState.HUB_NOT_INSTALLED, NexusStatus.of(NexusInputs(NexusInstalled(true, false, false), hostIsNexus = false)))
        assertEquals(NexusState.BRIDGE_REFUSED, NexusStatus.of(nexus(refused = true)))
    }

    @Test fun aLookAlikeBridgeIsAStateOfItsOwnAndNeverUsable() {
        val untrusted = NexusInstalled(bridge = false, hub = true, rokidCoder = false, bridgeUntrusted = true)
        assertEquals(NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED, NexusStatus.of(NexusInputs(untrusted, hostIsNexus = false)))
        assertEquals(NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED, NexusStatus.of(nexus(untrusted)))
        assertEquals(NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED, NexusStatus.of(NexusInputs(all, hostIsNexus = true, bridgeUntrusted = true, bound = true, lastCode = NexusResult.OK)))
        assertEquals("Bridge not trusted", NexusStatus.title(NexusState.BRIDGE_NOT_INSTALLED_UNTRUSTED))
    }

    @Test fun offByDefaultWhenBothAreInstalled() {
        assertEquals(NexusState.OFF, NexusStatus.of(NexusInputs(all, hostIsNexus = false)))
    }

    @Test fun theHubsAnswersMapToTheStates() {
        assertEquals(NexusState.WAITING, NexusStatus.of(nexus(bound = false)))
        assertEquals(NexusState.WAITING, NexusStatus.of(nexus(code = null)))
        assertEquals(NexusState.WAITING, NexusStatus.of(nexus(code = NexusResult.ERROR)))
        assertEquals(NexusState.PENDING_APPROVAL, NexusStatus.of(nexus(code = NexusResult.NOT_REGISTERED)))
        assertEquals(NexusState.APPROVED, NexusStatus.of(nexus(code = NexusResult.OK)))
        assertEquals(NexusState.APPROVED, NexusStatus.of(nexus(code = NexusResult.BUSY)))
        assertEquals(NexusState.CAPABILITY_DENIED, NexusStatus.of(nexus(code = NexusResult.NOT_GRANTED)))
        assertEquals(NexusState.HUB_STOPPED, NexusStatus.of(nexus(code = NexusResult.NOT_AVAILABLE)))
    }

    @Test fun aLinkTheHubCannotGetIsBusyWhenSomeoneElseCouldHoldIt() {
        assertEquals(NexusState.LINK_BUSY, NexusStatus.of(nexus(code = NexusResult.NOT_AVAILABLE, pcaLinkUp = true)))
        assertEquals(NexusState.LINK_BUSY, NexusStatus.of(nexus(NexusInstalled(true, true, true), code = NexusResult.NOT_AVAILABLE)))
    }

    @Test fun everyStateHasItsOwnTitleAndText() {
        val titles = NexusState.entries.map(NexusStatus::title)
        assertEquals("titles are distinct", NexusState.entries.size, titles.toSet().size)
        assertEquals(NexusState.entries.size, NexusState.entries.map(NexusStatus::detail).toSet().size)
        assertTrue(NexusState.entries.all { NexusStatus.title(it).isNotBlank() && NexusStatus.detail(it).length > 20 })
        // the seven states of the ruling
        assertEquals("Bridge not installed", NexusStatus.title(NexusState.BRIDGE_NOT_INSTALLED))
        assertEquals("Nexus not installed", NexusStatus.title(NexusState.HUB_NOT_INSTALLED))
        assertEquals("Pending approval", NexusStatus.title(NexusState.PENDING_APPROVAL))
        assertEquals("Approved", NexusStatus.title(NexusState.APPROVED))
        assertEquals("Capability denied", NexusStatus.title(NexusState.CAPABILITY_DENIED))
        assertEquals("Nexus hub stopped", NexusStatus.title(NexusState.HUB_STOPPED))
        assertEquals("Glasses link busy", NexusStatus.title(NexusState.LINK_BUSY))
    }

    @Test fun theTrustTextSaysWhatThePrivacyRulingRequires() {
        val t = NexusStatus.TRUST_LINES.joinToString("\n")
        for (needle in listOf("24 hours", "Forget", "Delete all", "switch Nexus off", "one at a time", "never installs, starts or takes over")) assertTrue(needle, needle in t)
        assertTrue("one at a time", "run one at a time" in NexusStatus.ROKID_CODER_NOTE && "never takes the link" in NexusStatus.ROKID_CODER_NOTE)
    }

    @Test fun thePackagesAreTheOnesTheManifestQueries() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        for (p in listOf(NexusPackages.BRIDGE, NexusPackages.HUB, NexusPackages.ROKID_CODER)) assertTrue(p, "<package android:name=\"$p\" />" in manifest)
    }
}
