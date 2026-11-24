package com.rokid.context.ai.datasaver

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataPolicy
import com.rokid.context.core.net.DataReservation
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.NetSnapshot
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Codex data saver r2: P1-3 (free Wi-Fi survives an unreadable abroad/hotspot switch), P1-4 (counters validated before the decision), P1-5 (durable reservations), P2-8 (own reservation excluded). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataSaverR2Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val mb = 1024L * 1024L
    private val wifi = NetSnapshot(true, Transport.WIFI, validated = true, notMetered = true, notRoaming = true)
    private val home = NetSnapshot(true, Transport.CELLULAR, validated = true, notMetered = false, notRoaming = true)
    private val roaming = home.copy(notRoaming = false)

    @Test fun aWrongTypedAbroadOrHotspotSwitchNeverTurnsValidatedHomeWifiIntoRoaming() {
        val p = prefs("r2_p3")
        p.edit().putString("abroad", "yes").putString("wifi_metered_abroad", "yes").commit()
        val g = DataSaverGraph(DataSaverStore(p), { wifi }, epochDay = { 100L })
        assertTrue(g.settingsNow().unreadable)
        assertEquals("independently validated unmetered Wi-Fi stays free", NetworkCost.FREE, g.costNow())
        for (k in DataKind.entries) assertTrue(k.name, g.decide(k, EndpointReach.REMOTE).allowed)
        assertFalse("the Wi-Fi is not held back", g.heldBack())
    }

    @Test fun anUnreadableSettingStillRefusesCostlyTrafficOnMobileData() {
        val p = prefs("r2_p3b")
        p.edit().putString("wifi_metered_abroad", "yes").commit()
        var snap = home
        val g = DataSaverGraph(DataSaverStore(p), { snap }, epochDay = { 100L })
        assertFalse(g.decide(DataKind.SMALL, EndpointReach.REMOTE).allowed)
        assertEquals(DataPolicy.R_UNREADABLE, g.decide(DataKind.PHOTO, EndpointReach.REMOTE).reason)
        snap = wifi.copy(notMetered = false) // a metered Wi-Fi is not independently free
        assertFalse(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
    }

    @Test fun aValidHotspotSwitchStillMakesWifiRoamingWhileAbroad() {
        val store = DataSaverStore(prefs("r2_p3c")).also { it.setAbroad(true); it.setWifiMeteredAbroad(true) }
        val g = DataSaverGraph(store, { wifi }, epochDay = { 100L })
        assertEquals(NetworkCost.ROAMING, g.costNow())
    }

    @Test fun aCorruptCounterFoundDuringTheAdmissionMakesThatSameAdmissionUnreadable() {
        val p = prefs("r2_p4")
        p.edit().putString("mobile_bytes_photo", "lots").commit()
        val store = DataSaverStore(p)
        val g = DataSaverGraph(store, { home }, epochDay = { 100L })
        assertFalse("not unreadable until the counter is read", store.settings.value.unreadable)
        // a single-check caller (embeddings, endpoint test, tiles): the FIRST decide must already refuse
        val d = g.decide(DataKind.SMALL, EndpointReach.REMOTE)
        assertFalse(d.allowed); assertEquals(DataPolicy.R_UNREADABLE, d.reason)
    }

    @Test fun aCorruptCounterIsAlsoCaughtByTheReservation() {
        val p = prefs("r2_p4b")
        p.edit().putString("mobile_bytes_small", "lots").commit()
        val g = DataSaverGraph(DataSaverStore(p), { roaming }, epochDay = { 100L })
        val r = g.reserve(DataKind.SMALL, 100, 0)
        assertNotNull(r); assertTrue("refused", r!!.failed)
    }

    /** A prefs file whose synchronous commit() fails (disk full, I/O error). */
    private fun failingCommit(real: SharedPreferences): SharedPreferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, a ->
        if (m.name == "edit") {
            val ed = real.edit()
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { prox, em, ea ->
                if (em.name == "commit") false else { val r = em.invoke(ed, *(ea ?: emptyArray())); if (r === ed) prox else r }
            }
        } else m.invoke(real, *(a ?: emptyArray()))
    } as SharedPreferences

    @Test fun aReservationThatCannotBeCommittedRefusesTheCostlySend() {
        val g = DataSaverGraph(DataSaverStore(failingCommit(prefs("r2_p5"))), { roaming }, epochDay = { 100L })
        val r = g.reserve(DataKind.PHOTO, 1000, 1)
        assertTrue("the send is refused", r != null && r.failed)
    }

    @Test fun aCommittedReservationIsOnDiskImmediately() {
        val p = prefs("r2_p5b")
        val g = DataSaverGraph(DataSaverStore(p), { roaming }, epochDay = { 100L })
        val r = g.reserve(DataKind.PHOTO, 3 * mb, 2)!!
        assertFalse(r.failed)
        assertEquals(3 * mb, p.getLong("mobile_bytes_photo", 0)); assertEquals(2L, p.getLong("mobile_photos", 0))
    }

    @Test fun aRequestsOwnReservationNeverClosesItsOwnGate() {
        val g = DataSaverGraph(DataSaverStore(prefs("r2_p8")).also { it.setMode(DataMode.ANYWHERE); it.setAbroad(true) }, { roaming }, epochDay = { 100L })
        assertTrue(g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        val r: DataReservation = g.reserve(DataKind.PHOTO, 11 * mb, 1)!! // the request's own bytes take the day over the 10 MB cap
        assertFalse("a NEW request is refused", g.decide(DataKind.PHOTO, EndpointReach.REMOTE).allowed)
        assertTrue("the admitted request is not cancelled by its own reservation", g.decideExcluding(DataKind.PHOTO, EndpointReach.REMOTE, r).allowed)
        g.reserve(DataKind.PHOTO, 11 * mb, 1) // another request's reservation still counts
        assertFalse(g.decideExcluding(DataKind.PHOTO, EndpointReach.REMOTE, r).allowed)
    }
}
