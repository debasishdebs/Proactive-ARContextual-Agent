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

private val DIRECT = java.util.concurrent.Executor { it.run() }

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
        val a = ModelPrivacyStore(p, s, DIRECT)
        a.set(ModelPrivacy.OFFLINE_ONLY)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, a.current()); assertEquals(ModelPrivacy.OFFLINE_ONLY, a.mode.value)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s, DIRECT).current())
        a.set(ModelPrivacy.AUTO); assertEquals(ModelPrivacy.AUTO, a.current()); assertEquals(ModelPrivacy.AUTO, ModelPrivacyStore(p, s, DIRECT).current())
        assertFalse(a.wasReset.value)
    }

    // ---- P2-2: lost settings fail closed ----

    @Test fun neverSetIsAutoAndNotReset() {
        val st = ModelPrivacyStore(prefs("mp9"), prefs("mp9s"), DIRECT)
        assertEquals(ModelPrivacy.AUTO, st.current()); assertFalse(st.wasReset.value)
    }

    @Test fun anEmptiedMainFileAfterAChoiceReadsOfflineOnlyAndSaysItWasReset() {
        val p = prefs("mp10"); val s = prefs("mp10s")
        ModelPrivacyStore(p, s, DIRECT).set(ModelPrivacy.OFFLINE_ONLY)
        p.edit().clear().commit() // a corrupted ai_backend.xml loads as an empty map
        val st = ModelPrivacyStore(p, s, DIRECT)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, st.current()); assertTrue(st.wasReset.value)
        st.set(ModelPrivacy.AUTO) // the user checks and chooses
        assertEquals(ModelPrivacy.AUTO, st.current()); assertFalse(st.wasReset.value)
    }

    @Test fun aLostSentinelAloneOrALostMainFileAloneStillFailsClosed() {
        val p = prefs("mp11"); val s = prefs("mp11s")
        ModelPrivacyStore(p, s, DIRECT).set(ModelPrivacy.AUTO)
        p.edit().remove("mode").commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s, DIRECT).current()) // the main file lost its mode: sentinel (and its own flag) remember
        s.edit().clear().commit(); p.edit().clear().commit()
        assertEquals("both gone = indistinguishable from a fresh install", ModelPrivacy.AUTO, ModelPrivacyStore(p, s, DIRECT).current())
        ModelPrivacyStore(p, s, DIRECT).set(ModelPrivacy.AUTO); p.edit().clear().commit(); s.edit().putBoolean("initialised", true).commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s, DIRECT).current())
    }

    // ---- P2-1: a lost or failed write is never Automatic ----

    /** Prefs whose commit() can be made to fail; apply() and reads go to the real file. */
    private class FlakyPrefs(private val real: SharedPreferences, var failCommits: Int = 0) : SharedPreferences by real {
        var commits = 0
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val e = real.edit()
            override fun putString(k: String?, v: String?) = apply { e.putString(k, v) }
            override fun putBoolean(k: String?, v: Boolean) = apply { e.putBoolean(k, v) }
            override fun remove(k: String?) = apply { e.remove(k) }
            override fun clear() = apply { e.clear() }
            override fun putInt(k: String?, v: Int) = apply { e.putInt(k, v) }
            override fun putLong(k: String?, v: Long) = apply { e.putLong(k, v) }
            override fun putFloat(k: String?, v: Float) = apply { e.putFloat(k, v) }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { e.putStringSet(k, v) }
            override fun apply() = e.apply()
            override fun commit(): Boolean { commits++; if (failCommits > 0) { failCommits--; return false }; return e.commit() }
        }
    }

    @Test fun aKillBeforeTheDurableWriteReadsBackAsResetOfflineOnlyNeverAutomatic() {
        val p = prefs("mp12"); val s = prefs("mp12s")
        ModelPrivacyStore(p, s, DIRECT).set(ModelPrivacy.AUTO) // an earlier, permissive choice is on the disk
        val neverRuns = java.util.concurrent.Executor { } // the process dies before the writer thread runs
        ModelPrivacyStore(p, s, neverRuns).set(ModelPrivacy.OFFLINE_ONLY)
        val next = ModelPrivacyStore(p, s, DIRECT)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, next.current()); assertTrue(next.wasReset.value)
    }

    @Test fun aCommitThatKeepsReturningFalseAlsoReadsBackAsResetOfflineOnly() {
        val rp = prefs("mp13"); val rs = prefs("mp13s")
        val st = ModelPrivacyStore(FlakyPrefs(rp, failCommits = 100), FlakyPrefs(rs, failCommits = 100), DIRECT); st.set(ModelPrivacy.AUTO) // first ever choice, nothing durable
        st.set(ModelPrivacy.OFFLINE_ONLY)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, st.current())
        val next = ModelPrivacyStore(rp, rs, DIRECT)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, next.current()); assertTrue("the begin step reached the disk through apply()", next.wasReset.value)
    }

    @Test fun aFailedCommitIsRetriedOnceAndThenSucceeds() {
        val rp = prefs("mp14"); val rs = prefs("mp14s")
        val st = ModelPrivacyStore(FlakyPrefs(rp, failCommits = 1), rs, DIRECT)
        st.set(ModelPrivacy.OFFLINE_ONLY)
        val next = ModelPrivacyStore(rp, rs, DIRECT)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, next.current()); assertFalse("the retry made it durable", next.wasReset.value)
    }

    @Test fun setAndWaitReportsWhetherTheWriteIsDurable() = kotlinx.coroutines.runBlocking {
        val p1 = prefs("mp15"); val s1 = prefs("mp15s")
        assertTrue(ModelPrivacyStore(p1, s1, DIRECT).setAndWait(ModelPrivacy.OFFLINE_ONLY))
        assertFalse(ModelPrivacyStore(FlakyPrefs(prefs("mp16"), failCommits = 100), prefs("mp16s"), DIRECT).setAndWait(ModelPrivacy.OFFLINE_ONLY))
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p1, s1, DIRECT).current())
    }

    @Test fun aSupersededWriteDoesNotOverwriteANewerChoice() {
        val p = prefs("mp17"); val s = prefs("mp17s")
        val queued = mutableListOf<Runnable>()
        val st = ModelPrivacyStore(p, s, java.util.concurrent.Executor { queued += it })
        st.set(ModelPrivacy.OFFLINE_ONLY); st.set(ModelPrivacy.AUTO)
        queued.reversed().forEach { it.run() } // the newer write runs first, the older one after
        assertEquals(ModelPrivacy.AUTO, ModelPrivacyStore(p, s, DIRECT).current())
    }

    @Test fun theSentinelFileNameIsTheOneBackupRulesExclude() = assertEquals("ai_backend_init", ModelPrivacyStore.SENTINEL_PREFS)

    @Test fun anUnknownOrWronglyTypedStoredValueFailsClosedToOfflineOnly() {
        val p = prefs("mp3")
        p.edit().putString("mode", "SOMETHING_NEW").commit()
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p).current())
        assertTrue("an unknown value counts as reset, so the screen says so", ModelPrivacyStore(p).wasReset.value)
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
