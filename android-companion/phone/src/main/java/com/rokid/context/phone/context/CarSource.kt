package com.rokid.context.phone.context

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Observer
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.Evidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * `phone_car`: a car link from Bluetooth (an address in the user's "My car" list, or device class `AUDIO_VIDEO_CAR_AUDIO`) and
 * from Android Auto ([CarConnection]); spec §5 and the car rule §6.5 (a connected listed device = a vehicle episode).
 *
 * Bluetooth state is tracked per `(address, profile)`: a device on A2DP and HEADSET is up until both drop, and a kind is
 * connected while any device is. The kind of a connected device is recomputed whenever the "My car" list changes ([recompute]).
 * Addresses live in memory only and are never logged. Link changes are current-state notifications (no observation time), so they
 * are stamped now; the initial profile-proxy snapshot never overrides a broadcast already seen in this run ([onBtSnapshot]).
 * Known residual risk (accepted): without the Android Auto app installed, another app could claim the `androidx.car.app.connection`
 * provider authority and report PROJECTION, faking an ANDROID_AUTO link (prior 0.85 = DRIVING = nudges off; capture is never changed by it).
 * [start]/[stop] may run on a Looper-less thread; the shell hops to the main thread where Android needs it.
 */
class CarSource(
    private val sinkFor: (String) -> EvidenceSink,
    private val time: ObsTime = ObsTime(),
    private val myCars: () -> Set<String>,
    private val changes: Flow<Int>? = null,
    private val scope: CoroutineScope? = null,
    private val platform: Platform,
) : ContextSource {
    interface Platform {
        /** Registers the Bluetooth receivers and profile proxies; false = no permission or no adapter (Android Auto stays on). */
        fun startBluetooth(): Boolean
        fun stopBluetooth()
        fun startAndroidAuto()
        fun stopAndroidAuto()
    }

    override val id = ID
    private val accepted = AtomicInteger(); private val ignoredN = AtomicInteger()
    override val events: Int get() = accepted.get()
    val ignored: Int get() = ignoredN.get()

    private data class Key(val address: String, val profile: Int)
    private var sink: EvidenceSink? = null
    private val up = HashMap<Key, Int?>()          // connected (address, profile) -> device class
    private val touched = HashSet<Key>()           // keys a broadcast has spoken about in this run
    private var aa = false
    private var emitted = emptySet<CarLinkKind>()
    private var job: Job? = null

    override fun available(): Boolean = true

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        sink = sinkFor(ID)
        up.clear(); touched.clear(); aa = false; emitted = emptySet()
        runCatching { platform.startBluetooth() }
        runCatching { platform.startAndroidAuto() }
        val f = changes; val sc = scope
        if (f != null && sc != null) job = sc.launch { f.collect { recompute() } }
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        if (sink == null) return
        sink = null
        job?.cancel(); job = null
        runCatching { platform.stopBluetooth() }
        runCatching { platform.stopAndroidAuto() }
        up.clear(); touched.clear(); aa = false; emitted = emptySet()
    }

    /** The kinds connected now (Bluetooth and Android Auto). */
    @Synchronized fun currentKinds(): Set<CarLinkKind> = kinds()

    private fun kindOf(address: String, deviceClass: Int?): CarLinkKind? = when {
        myCars().any { it.equals(address, ignoreCase = true) } -> CarLinkKind.MY_CAR_BT
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> CarLinkKind.CAR_CLASS_BT
        else -> null
    }

    private fun kinds(): Set<CarLinkKind> {
        val s = HashSet<CarLinkKind>()
        for ((k, cls) in up) kindOf(k.address, cls)?.let { s += it }
        if (aa) s += CarLinkKind.ANDROID_AUTO
        return s
    }

    /** One Bluetooth connection change. Devices that are neither listed nor car class are ignored (counted). */
    @Synchronized
    fun onBtDevice(address: String, deviceClass: Int?, connected: Boolean, profile: Int = BluetoothProfile.A2DP) {
        if (sink == null) return
        val key = Key(address.uppercase(), profile)
        touched += key
        // Every connected device is kept (in memory), so adding it to "My car" later re-derives its kind; only the count notes non-cars.
        if (connected) { if (kindOf(key.address, deviceClass) == null) ignoredN.incrementAndGet(); up[key] = deviceClass } else up.remove(key)
        publish()
    }

    /** The profile proxy's connected devices at start: only keys no broadcast has spoken about (a stale list cannot re-pin a link). */
    @Synchronized
    fun onBtSnapshot(profile: Int, devices: List<Pair<String, Int?>>) {
        if (sink == null) return
        for ((a, c) in devices) {
            val key = Key(a.uppercase(), profile)
            if (key in touched) continue
            touched += key
            if (kindOf(key.address, c) == null) ignoredN.incrementAndGet()
            up[key] = c
        }
        publish()
    }

    /**
     * `BluetoothAdapter.ACTION_STATE_CHANGED`: when Bluetooth turns off, every Bluetooth link is gone whether or not the profile
     * broadcasts said so (some OEMs skip them). The profile proxies rebind when it turns back on and report again ([onBtSnapshot]).
     */
    @Synchronized
    fun onBluetoothState(state: Int) {
        if (sink == null) return
        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) { up.clear(); touched.clear(); publish() }
    }

    /** [CarConnection] type: PROJECTION or NATIVE = connected, anything else = disconnected. */
    @Synchronized
    fun onAndroidAuto(type: Int) {
        if (sink == null) return
        aa = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        publish()
    }

    /** Re-derives every connected device's kind (the "My car" list changed while it was connected). */
    @Synchronized fun recompute() { if (sink != null) publish() }

    /** Emits a link event for every kind whose any-connected flag flipped; connects first so a swap never shows a gap. */
    private fun publish() {
        val out = sink ?: return
        val now = kinds()
        val at = time.toWall(time.nowElapsed())
        for (k in CarLinkKind.values()) if (k in now && k !in emitted) { accepted.incrementAndGet(); out.accept(Evidence.CarLink(at, k, true)) }
        for (k in CarLinkKind.values()) if (k !in now && k in emitted) { accepted.incrementAndGet(); out.accept(Evidence.CarLink(at, k, false)) }
        emitted = now
    }

    /**
     * The profile proxies of Bluetooth runs. A run token says which run a proxy belongs to: a proxy that connects after a stop, or
     * after a quick stop/start began a newer run, is refused ([add] false) and the caller closes it, so no binding leaks.
     */
    internal class ProxyHolder<T> {
        private var token = 0
        private var open = false
        private val proxies = HashMap<Int, T>()

        @Synchronized fun beginRun(): Int { token++; open = true; proxies.clear(); return token }
        @Synchronized fun add(run: Int, profile: Int, proxy: T): Boolean { if (!open || run != token) return false; proxies[profile] = proxy; return true }
        @Synchronized fun remove(run: Int, profile: Int) { if (run == token) proxies.remove(profile) }
        /** Ends the run and returns the proxies to close. */
        @Synchronized fun endRun(): List<Pair<Int, T>> { open = false; val r = proxies.toList(); proxies.clear(); return r }
    }

    companion object {
        const val ID = "phone_car"

        fun create(context: Context, sinkFor: (String) -> EvidenceSink, myCars: () -> Set<String>, changes: Flow<Int>, scope: CoroutineScope): CarSource {
            lateinit var self: CarSource
            self = CarSource(sinkFor, myCars = myCars, changes = changes, scope = scope, platform = AndroidPlatform(context.applicationContext) { self })
            return self
        }

        /** (address, display name) of the bonded devices for the picker; empty without the permission. Display only, never logged or stored. */
        fun bondedDevicesForPicker(context: Context): List<Pair<String, String>> =
            bondedDevicesForPickerOrEmpty(hasBluetoothPermission(context)) {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                @SuppressLint("MissingPermission") // checked above
                val bonded = adapter?.bondedDevices.orEmpty()
                bonded.map { it.address to (runCatching { it.name }.getOrNull() ?: it.address) }
            }

        internal fun bondedDevicesForPickerOrEmpty(permitted: Boolean, read: () -> List<Pair<String, String>>): List<Pair<String, String>> =
            if (!permitted) emptyList() else runCatching(read).getOrDefault(emptyList())

        internal fun hasBluetoothPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // every Bluetooth call is behind hasBluetoothPermission
    private class AndroidPlatform(private val ctx: Context, private val source: () -> CarSource) : Platform {
        private val main = Handler(Looper.getMainLooper())
        private var receiver: BroadcastReceiver? = null
        private var adapter: BluetoothAdapter? = null
        private val holder = ProxyHolder<BluetoothProfile>()
        private var observer: Observer<Int>? = null
        private var connection: CarConnection? = null

        @Synchronized
        override fun startBluetooth(): Boolean {
            if (!hasBluetoothPermission(ctx)) return false
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            adapter = a; val token = holder.beginRun()
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        runCatching { source().onBluetoothState(i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) }
                        return
                    }
                    val profile = if (i.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) BluetoothProfile.HEADSET else BluetoothProfile.A2DP
                    val state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val connected = when (state) { BluetoothProfile.STATE_CONNECTED -> true; BluetoothProfile.STATE_DISCONNECTED -> false; else -> return }
                    val d = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    runCatching { source().onBtDevice(d.address, d.bluetoothClass?.deviceClass, connected, profile) }
                }
            }
            // RECEIVER_EXPORTED on purpose: these are protected broadcasts sent by the Bluetooth app (uid 1002), not by system_server, so a
            // NOT_EXPORTED receiver would never be delivered them. No other app can send a protected action, so exporting is safe.
            ContextCompat.registerReceiver(
                ctx, r,
                IntentFilter().apply {
                    addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED); addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = r
            for (p in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        // connected after stop() or after a newer run began: release it, it belongs to no run
                        if (!holder.add(token, profile, proxy)) { runCatching { a.closeProfileProxy(profile, proxy) }; return }
                        runCatching { source().onBtSnapshot(profile, proxy.connectedDevices.map { it.address to it.bluetoothClass?.deviceClass }) }
                    }
                    override fun onServiceDisconnected(profile: Int) { holder.remove(token, profile) }
                }, p)
            }
            return true
        }

        @Synchronized
        override fun stopBluetooth() {
            receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null
            val a = adapter; adapter = null
            for ((p, proxy) in holder.endRun()) runCatching { a?.closeProfileProxy(p, proxy) }
        }

        override fun startAndroidAuto() {
            main.post {
                runCatching {
                    val o = Observer<Int> { source().onAndroidAuto(it) }
                    val c = CarConnection(ctx)
                    c.type.observeForever(o)
                    observer = o; connection = c
                }
            }
        }

        override fun stopAndroidAuto() {
            main.post {
                runCatching { observer?.let { connection?.type?.removeObserver(it) } }
                observer = null; connection = null
            }
        }
    }
}
