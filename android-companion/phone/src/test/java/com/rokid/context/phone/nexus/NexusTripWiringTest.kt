package com.rokid.context.phone.nexus

import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v2.0: every close event of PCA reaches the Nexus provider's `trip`. The behaviour of `trip` itself is NexusProviderTest and the sink's own withdraw tests. */
class NexusTripWiringTest {
    private val root = File("..")
    private fun src(path: String) = File(root, path).readText()

    @Test fun forgetAndDeleteAllTripButRetentionExpiryDoesNot() = runTest {
        val tripped = mutableListOf<TripReason>()
        val l = NexusGraph.closeListener { tripped += it }
        l.onUserForget(); l.onDeleteAll()
        l.onFramesForgotten(listOf("f1")); l.onRangeForgotten(0, 1, emptyList()) // retention's expiry of photos calls these
        assertEquals(listOf(TripReason.FORGET, TripReason.DELETE_ALL), tripped)
    }

    @Test fun everyUserForgetPathAnnouncesItself() {
        val actions = src("services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt")
        // facts, photos, moments, scoped, multi-select (runLocked), marks (markOutcome) and a time range (forgetRangeOutcome)
        assertEquals(3, Regex("""ForgetListeners\.userForget\(\)""").findAll(actions).count())
        for (fn in listOf("private suspend fun runLocked", "private suspend fun markOutcome", "suspend fun forgetRangeOutcome")) assertTrue(fn, fn in actions)
        val range = actions.substringAfter("suspend fun forgetRangeOutcome").substringBefore("fun scrubQueue")
        assertTrue(range.contains("userForget"))
        val mark = actions.substringAfter("private suspend fun markOutcome").substringBefore("data class ScopedPreview")
        assertTrue(mark.contains("userForget"))
    }

    @Test fun theOtherCloseEventsAreWired() {
        assertTrue("zone hold", src("phone/src/main/java/com/rokid/context/phone/zone/ZoneGraph.kt").contains("NexusGraph.trip(io.github.pca.nexusport.TripReason.ZONE_HOLD)"))
        val graph = src("phone/src/main/java/com/rokid/context/phone/nexus/NexusGraph.kt")
        for (r in listOf("TripReason.PAUSE", "TripReason.NOT_WORN", "TripReason.LINK_LOST", "TripReason.FORGET", "TripReason.DELETE_ALL")) assertTrue(r, r in graph)
        assertTrue("mode off is the host switch", src("phone/src/main/java/com/rokid/context/phone/nexus/NexusProvider.kt").contains("TripReason.MODE_OFF"))
        val hubGraph = src("phone/src/main/java/com/rokid/context/phone/hub/HubGraph.kt")
        assertTrue("hub stop", hubGraph.contains("onStopped = com.rokid.context.phone.nexus.NexusGraph::onHubStopped"))
        assertTrue("the host is read from the store", hubGraph.contains("hudHost = { com.rokid.context.phone.nexus.NexusHostStore.of(app).current() }"))
    }

    @Test fun nothingInPcaTouchesTheBridgesPortExceptThroughTheProvider() {
        // PCA binds the bridge in ONE file; the AIDL port type itself is only the sink's (nexus-port), the provider hands it the binder.
        val offenders = File(root, "phone/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && "NexusPort.Stub" in it.readText() }.map { it.name }.toList()
        assertTrue(offenders.toString(), offenders.isEmpty())
        val binders = File(root, "phone/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" && "bindService" in it.readText() && "NexusPortService" in it.readText() }.map { it.name }.toList()
        assertEquals(listOf("NexusProvider.kt"), binders)
    }
}
