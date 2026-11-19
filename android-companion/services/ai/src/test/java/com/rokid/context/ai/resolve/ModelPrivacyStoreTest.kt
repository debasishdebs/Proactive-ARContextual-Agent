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

    @Test fun aSavedModeSurvivesANewStoreAndEveryReaderSeesItAtOnce() {
        val p = prefs("mp2")
        val a = ModelPrivacyStore(p); val b = ModelPrivacyStore(p)
        a.set(ModelPrivacy.OFFLINE_ONLY)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, b.current()); assertEquals(ModelPrivacy.OFFLINE_ONLY, a.mode.value)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p).current())
        a.set(ModelPrivacy.AUTO); assertEquals(ModelPrivacy.AUTO, b.current())
    }

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
}
