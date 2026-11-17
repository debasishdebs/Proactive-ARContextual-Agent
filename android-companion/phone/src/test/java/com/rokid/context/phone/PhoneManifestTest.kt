package com.rokid.context.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** READ_PHONE_STATE is declared (relaunch guard call state); it is requested at runtime in Settings and denial never blocks anything. */
class PhoneManifestTest {
    @Test fun readPhoneStateIsDeclared() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"android.permission.READ_PHONE_STATE\" />"))
    }

    @Test fun watchListenerIsDeclaredForThePcaMotionPath() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val svc = manifest.substringAfter("com.rokid.context.phone.context.PcaWatchListener").substringBefore("</service>")
        assertTrue(svc.contains("com.google.android.gms.wearable.MESSAGE_RECEIVED"))
        assertTrue(svc.contains("android:pathPrefix=\"/pca/motion\""))
    }

    @Test fun theLiveFitProviderAndItsVisibilityEntryAreGone() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("WatchMotionProvider"))
        assertFalse(manifest.contains("com.rokid.context.motionsource"))
        assertFalse(manifest.contains("com.livear.fit"))
    }

    @Test fun noLiveFitPackageStringRemainsInPhoneMainSources() {
        val hits = File("src/main").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.readText().contains("com.livear.fit") }.map { it.path }.toList()
        assertTrue(hits.toString(), hits.isEmpty())
    }

    /** v2.0 Rokid Nexus: the ONLY manifest additions are the bridge's signature permission and three package-visibility entries (bridge, Nexus hub, Rokid-Coder); no other new permission. */
    @Test fun theNexusAdditionsAreOneSignaturePermissionAndThreeQueries() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<uses-permission android:name=\"io.github.pca.nexusbridge.permission.BIND_NEXUS_PORT\" />"))
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.trim().lines().map { it.trim() }.sorted() == listOf(
            "<package android:name=\"com.anezium.rokidbus.phone\" />", "<package android:name=\"io.github.debasishdebs.rokidcoder\" />", "<package android:name=\"io.github.pca.nexusbridge\" />",
        ))
        val perms = Regex("<uses-permission android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(perms.toString(), perms.none { "nexus" in it.lowercase() && it != "io.github.pca.nexusbridge.permission.BIND_NEXUS_PORT" })
        // PCA declares no component of the bridge
        assertFalse(manifest.contains("NexusPortService"))
    }
}
