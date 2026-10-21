package com.rokid.context.phone.context

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothProfile
import androidx.car.app.connection.CarConnection
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.Evidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSourceTest {
    private val clock = FakeObsClock()
    private val got = ArrayList<Evidence>()
    private var myCars = emptySet<String>()
    private val changes = MutableStateFlow(0)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var sinkTaken = 0
    private var btStarted = 0
    private var btStopped = 0
    private var btOk = true

    private val CAR = BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
    private val HEADSET_CLASS = BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET
    private val A2DP = BluetoothProfile.A2DP
    private val HS = BluetoothProfile.HEADSET

    private fun src() = CarSource(
        sinkFor = { id -> assertEquals("phone_car", id); sinkTaken++; EvidenceSink { got += it } },
        time = clock.time(), myCars = { myCars }, changes = changes, scope = scope,
        platform = object : CarSource.Platform {
            override fun startBluetooth(): Boolean { btStarted++; return btOk }
            override fun stopBluetooth() { btStopped++ }
            override fun startAndroidAuto() {}
            override fun stopAndroidAuto() {}
        },
    )

    private fun started() = src().also { assertTrue(it.start()) }
    private fun links() = got.map { it as Evidence.CarLink }

    @Test fun idAndAvailable() { assertEquals("phone_car", src().id); assertTrue(src().available()) }

    @Test fun carClassConnectEmitsCarClassLink() {
        val s = started()
        s.onBtDevice("AA:01", CAR, true)
        assertEquals(listOf(Evidence.CarLink(clock.wall(), CarLinkKind.CAR_CLASS_BT, true)), got)
        assertEquals(setOf(CarLinkKind.CAR_CLASS_BT), s.currentKinds()); assertEquals(1, s.events)
    }

    @Test fun headsetClassIsIgnored() {
        val s = started()
        s.onBtDevice("AA:02", HEADSET_CLASS, true); s.onBtDevice("AA:03", null, true)
        assertTrue(got.isEmpty()); assertTrue(s.currentKinds().isEmpty()); assertEquals(2, s.ignored)
    }

    @Test fun listedAddressIsMyCarWhateverTheClass() {
        myCars = setOf("AA:04"); val s = started()
        s.onBtDevice("AA:04", HEADSET_CLASS, true)
        assertEquals(CarLinkKind.MY_CAR_BT, links().single().kind)
        s.onBtDevice("aa:04", null, false) // address case does not matter
        assertEquals(listOf(true, false), links().map { it.connected })
    }

    @Test fun twoCarDevicesOneDisconnectsNoEventBothDisconnects() {
        val s = started()
        s.onBtDevice("AA:05", CAR, true); s.onBtDevice("AA:06", CAR, true)
        assertEquals(1, got.size) // the kind was already connected
        s.onBtDevice("AA:05", CAR, false); assertEquals(1, got.size)
        s.onBtDevice("AA:06", CAR, false)
        assertEquals(listOf(true, false), links().map { it.connected })
    }

    @Test fun oneDeviceOnTwoProfilesStaysConnectedUntilBothDrop() {
        val s = started()
        s.onBtDevice("AA:07", CAR, true, A2DP); s.onBtDevice("AA:07", CAR, true, HS)
        s.onBtDevice("AA:07", CAR, false, HS)
        assertEquals(1, got.size); assertEquals(setOf(CarLinkKind.CAR_CLASS_BT), s.currentKinds()) // A2DP still up
        s.onBtDevice("AA:07", CAR, false, A2DP)
        assertEquals(listOf(true, false), links().map { it.connected })
    }

    @Test fun repeatedSameStateEmitsOnce() {
        val s = started()
        s.onBtDevice("AA:08", CAR, true); s.onBtDevice("AA:08", CAR, true)
        assertEquals(1, got.size)
        s.onBtDevice("AA:09", CAR, false) // never connected: nothing
        assertEquals(1, got.size)
    }

    @Test fun myCarListChangedWhileConnectedRecomputesTheKind() {
        val s = started()
        s.onBtDevice("AA:0A", HEADSET_CLASS, true) // not a car yet
        assertTrue(got.isEmpty())
        myCars = setOf("AA:0A"); changes.value++ // the user adds it to "My car" while it is connected
        assertEquals(listOf(Evidence.CarLink(clock.wall(), CarLinkKind.MY_CAR_BT, true)), got)
        myCars = emptySet(); changes.value++
        assertEquals(listOf(true, false), links().map { it.connected }) // and removes it again: no longer a car
    }

    @Test fun myCarListChangeSwapsACarClassDeviceToMyCar() {
        val s = started()
        s.onBtDevice("AA:0B", CAR, true)
        myCars = setOf("AA:0B"); s.recompute()
        assertEquals(listOf(CarLinkKind.CAR_CLASS_BT to true, CarLinkKind.MY_CAR_BT to true, CarLinkKind.CAR_CLASS_BT to false), links().map { it.kind to it.connected })
    }

    @Test fun androidAutoProjectionAndNativeConnectNotConnectedDisconnects() {
        val s = started()
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_NOT_CONNECTED); assertTrue(got.isEmpty())
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_PROJECTION)
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_NATIVE); s.onAndroidAuto(CarConnection.CONNECTION_TYPE_PROJECTION)
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        assertEquals(listOf(CarLinkKind.ANDROID_AUTO to true, CarLinkKind.ANDROID_AUTO to false), links().map { it.kind to it.connected })
    }

    @Test fun androidAutoIsIndependentOfBluetooth() {
        val s = started()
        s.onBtDevice("AA:0C", CAR, true); s.onAndroidAuto(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertEquals(setOf(CarLinkKind.CAR_CLASS_BT, CarLinkKind.ANDROID_AUTO), s.currentKinds())
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        assertEquals(setOf(CarLinkKind.CAR_CLASS_BT), s.currentKinds())
    }

    @Test fun bluetoothDeniedStillStartsAndroidAuto() {
        btOk = false
        val s = src(); assertTrue(s.start()); assertEquals(1, btStarted)
        s.onAndroidAuto(CarConnection.CONNECTION_TYPE_PROJECTION); assertEquals(1, got.size)
    }

    @Test fun aStaleSnapshotNeverOverridesALaterBroadcast() {
        val s = started()
        s.onBtDevice("AA:0D", CAR, true); s.onBtDevice("AA:0D", CAR, false) // broadcasts first
        s.onBtSnapshot(A2DP, listOf("AA:0D" to CAR))                          // the proxy answer was read before the disconnect
        assertEquals(listOf(true, false), links().map { it.connected }); assertTrue(s.currentKinds().isEmpty())
    }

    @Test fun snapshotAddsDevicesNotSeenYet() {
        val s = started()
        s.onBtSnapshot(A2DP, listOf("AA:0E" to CAR, "AA:0F" to HEADSET_CLASS))
        assertEquals(setOf(CarLinkKind.CAR_CLASS_BT), s.currentKinds())
    }

    @Test fun bluetoothOffClearsEveryLinkAndOnReSnapshots() {
        val s = started()
        s.onBtDevice("AA:20", CAR, true); s.onBtDevice("AA:21", CAR, true, HS)
        s.onBluetoothState(android.bluetooth.BluetoothAdapter.STATE_OFF)
        assertEquals(listOf(true, false), links().map { it.connected }); assertTrue(s.currentKinds().isEmpty())
        s.onBluetoothState(android.bluetooth.BluetoothAdapter.STATE_ON)
        s.onBtSnapshot(A2DP, listOf("AA:20" to CAR)) // the proxy rebinds and reports again
        assertEquals(listOf(true, false, true), links().map { it.connected })
    }

    @Test fun nothingBeforeStartOrAfterStopAndStopClearsState() {
        val s = src()
        s.onBtDevice("AA:10", CAR, true); assertTrue(got.isEmpty())
        s.start(); s.onBtDevice("AA:10", CAR, true); got.clear()
        s.stop(); assertEquals(1, btStopped)
        s.onBtDevice("AA:10", CAR, false); s.onAndroidAuto(CarConnection.CONNECTION_TYPE_PROJECTION)
        assertTrue(got.isEmpty()); assertTrue(s.currentKinds().isEmpty())
        s.start(); assertEquals(2, sinkTaken)
        s.onBtDevice("AA:10", CAR, true); assertEquals(1, got.size) // a fresh run emits again
    }

    @Test fun changesAfterStopAreNotObserved() {
        val s = started(); s.onBtDevice("AA:11", HEADSET_CLASS, true); s.stop()
        myCars = setOf("AA:11"); changes.value++
        assertTrue(got.isEmpty())
        scope.cancel()
    }

    @Test fun pickerListIsEmptyWithoutPermission() {
        assertFalse(CarSource.bondedDevicesForPickerOrEmpty(permitted = false) { listOf("A" to "B") }.isNotEmpty())
        assertEquals(listOf("A" to "B"), CarSource.bondedDevicesForPickerOrEmpty(permitted = true) { listOf("A" to "B") })
    }

    @Test fun aProxyFromAnEarlierRunIsRefusedAndOnlyTheCurrentRunsAreDrained() {
        val h = CarSource.ProxyHolder<String>()
        val run1 = h.beginRun()
        val run2 = h.beginRun() // quick stop/start: a new run before run 1's proxy connected
        assertFalse("a late connect of run 1 must be closed by the caller", h.add(run1, 2, "late1"))
        assertTrue(h.add(run2, 2, "p2")); assertTrue(h.add(run2, 1, "h2"))
        assertEquals(setOf(2 to "p2", 1 to "h2"), h.endRun().toSet())
        assertFalse("after the run ended nothing is accepted", h.add(run2, 2, "again"))
        assertTrue(h.endRun().isEmpty())
    }

    @Test fun aProxyThatDisconnectsIsForgotten() {
        val h = CarSource.ProxyHolder<String>(); val r = h.beginRun()
        h.add(r, 2, "p"); h.remove(r, 2)
        assertTrue(h.endRun().isEmpty())
    }
}
