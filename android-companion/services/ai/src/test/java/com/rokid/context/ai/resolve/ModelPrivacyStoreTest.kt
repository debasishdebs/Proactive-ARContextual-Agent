package com.rokid.context.ai.resolve

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.Transport
import com.rokid.context.ai.datasaver.DataSaverGraph
import com.rokid.context.ai.datasaver.DataSaverStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelPrivacyStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun theDefaultIsAutoAndTheFileIsTheOneBackupRulesExclude() {
        assertEquals(ModelPrivacy.AUTO, ModelPrivacyStore(prefs("mp1")).current())
        assertEquals("ai_backend", ModelPrivacyStore.PREFS)
    }

    @Test fun aSavedModeSurvivesANewStoreAndTheOneStoreIsAuthoritativeAtOnce() {
        val p = prefs("mp2"); val s = prefs("mp2s")
        val a = ModelPrivacyStore(p, s)
        a.set(ModelPrivacy.OFFLINE_ONLY)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, a.current()); assertEquals(ModelPrivacy.OFFLINE_ONLY, a.mode.value)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s).current())
        a.set(ModelPrivacy.AUTO); assertEquals(ModelPrivacy.AUTO, a.current()); assertEquals(ModelPrivacy.AUTO, ModelPrivacyStore(p, s).current())
        assertFalse(a.wasReset.value)
    }

    // ---- P2-2: lost settings fail closed ----

    @Test fun neverSetIsAutoAndNotReset() {
        val st = ModelPrivacyStore(prefs("mp9"), prefs("mp9s"))
        assertEquals(ModelPrivacy.AUTO, st.current()); assertFalse(st.wasReset.value)
    }

    @Test fun anEmptiedMainFileAfterAChoiceReadsOfflineOnlyAndSaysItWasReset() {
        val p = prefs("mp10"); val s = prefs("mp10s")
        ModelPrivacyStore(p, s).set(ModelPrivacy.OFFLINE_ONLY)
        p.edit().clear().commit() // a corrupted ai_backend.xml loads as an empty map
        val st = ModelPrivacyStore(p, s)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, st.current()); assertTrue(st.wasReset.value)
        st.set(ModelPrivacy.AUTO) // the user checks and chooses
        assertEquals(ModelPrivacy.AUTO, st.current()); assertFalse(st.wasReset.value)
    }

    @Test fun aLostSentinelAloneOrALostMainFileAloneStillFailsClosed() {
        val p = prefs("mp11"); val s = prefs("mp11s")
        ModelPrivacyStore(p, s).set(ModelPrivacy.AUTO)
        p.edit().remove("mode").commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s).current()) // the main file lost its mode: sentinel (and its own flag) remember
        s.edit().clear().commit(); p.edit().clear().commit()
        assertEquals("both gone = indistinguishable from a fresh install", ModelPrivacy.AUTO, ModelPrivacyStore(p, s).current())
        ModelPrivacyStore(p, s).set(ModelPrivacy.AUTO); p.edit().clear().commit(); s.edit().putBoolean("initialised", true).commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s).current())
    }

    @Test fun theSentinelFileNameIsTheOneBackupRulesExclude() = assertEquals("ai_backend_init", ModelPrivacyStore.SENTINEL_PREFS)

    @Test fun anUnknownOrWronglyTypedStoredValueFailsClosedToOfflineOnly() {
        val p = prefs("mp3")
        p.edit().putString("mode", "SOMETHING_NEW").commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p).current())
        p.edit().clear().putInt("mode", 7).commit() // a ClassCastException on read
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p).current())
    }

    @Test fun aPrefsFileThatThrowsReadsAsOfflineOnly() {
        val throwing = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ -> throw IllegalStateException("gone") } as SharedPreferences
        // the constructor reads once: it must not throw, and must say Offline only
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(throwing).current())
    }

    // ---- the data saver gate, which every caller that parks (keyframe loop, queue, Ask photos, Place Guide) asks ----

    private val wifi = NetSnapshot(true, Transport.WIFI, validated = true, notMetered = true, notRoaming = true)

    @Test fun offlineOnlyClosesTheDataGateForRemoteEndpointsEvenOnFreeWifiAndLeavesTheDeviceAndLanOpen() {
        var m = ModelPrivacy.OFFLINE_ONLY
        val g = DataSaverGraph(DataSaverStore(prefs("mp4")), { wifi }, { 100L }, privacy = { m })
        for (k in DataKind.entries) {
            val d = g.decide(k, EndpointReach.REMOTE)
            assertFalse(k.name, d.allowed); assertEquals("offline_only", d.reason)
            assertTrue(g.decide(k, EndpointReach.DEVICE).allowed)
            assertTrue(k.name, g.decide(k, EndpointReach.LAN).allowed) // Wi-Fi: the user's own network
        }
        m = ModelPrivacy.AUTO
        for (k in DataKind.entries) assertTrue(k.name, g.decide(k, EndpointReach.REMOTE).allowed) // free Wi-Fi again
    }

    @Test fun anUnreadablePrivacySettingClosesTheGate() {
        val g = DataSaverGraph(DataSaverStore(prefs("mp5")), { wifi }, { 100L }, privacy = { error("prefs") })
        assertFalse(g.decide(DataKind.SMALL, EndpointReach.REMOTE).allowed)
    }

    @Test fun theDefaultGraphStillBehavesExactlyAsBefore() {
        val g = DataSaverGraph(DataSaverStore(prefs("mp6")), { wifi }, { 100L })
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    // ---- parked frames: Offline only is a standing refusal, not a blip, and not a data cost ----

    @Test fun offlineOnlyHoldsPhotosBackForARemoteEndpointEvenDuringAPreValidationBlipAndMarksTheBacklog() {
        val blip = NetSnapshot(true, Transport.WIFI, validated = false, notMetered = true, notRoaming = true, unvalidatedSinceMs = System.currentTimeMillis() - 1_000) // a fresh Wi-Fi reconnect: cost UNKNOWN for a moment
        var m = ModelPrivacy.OFFLINE_ONLY
        val g = DataSaverGraph(DataSaverStore(prefs("mp7")), { blip }, { 100L }, privacy = { m })
        assertTrue(g.heldBack())
        g.skipped(DataKind.PHOTO, com.rokid.context.core.net.Skipped.UPLOAD_QUEUED, g.decide(DataKind.PHOTO, EndpointReach.REMOTE))
        assertTrue("the backlog is marked, so it is parked and protected", g.blockedSince() != 0L)
        g.reachProvider = { EndpointReach.DEVICE }
        assertFalse("an on-device photo endpoint is not held back", g.heldBack())
        m = ModelPrivacy.AUTO; g.reachProvider = { EndpointReach.REMOTE }
        assertFalse("AUTO: the blip debounce applies as before", g.heldBack())
    }

    @Test fun offlineOnlyRefusalsAreLoggedButNotCountedInBlockedToday() {
        val g = DataSaverGraph(DataSaverStore(prefs("mp8")), { wifi }, { 100L }, privacy = { ModelPrivacy.OFFLINE_ONLY })
        g.skipped(DataKind.PHOTO, com.rokid.context.core.net.Skipped.UPLOAD_QUEUED, g.decide(DataKind.PHOTO, EndpointReach.REMOTE))
        assertEquals(0L, g.ledger.today().total)
    }
}
