package com.rokid.context.phone.hub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.rokid.context.core.log.PcLog
import com.rokid.context.link.CxrGlassesLink

/**
 * v1.1 T6: the Bluetooth ACL connect of the PAIRED glasses (and no other device) is the one wake signal a stopped hub can observe (its wear signal lives inside a running session).
 * It only SUBMITS [AutoStopEvent.GlassesNearby]; the reducer decides (a manual stop is never overridden), and any start goes through the guarded path (the call guard, the hourly cap).
 * The address is read from the broadcast, compared and dropped: it is never logged, stored from here or sent anywhere.
 */
class GlassesAclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val app = context.applicationContext
        val address = runCatching { IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address }.getOrNull()
        if (address.isNullOrBlank()) return
        // review r1 P3: the stored-address read (SharedPreferences) and the match are off Main, inside the async section
        ReceiverWork.run(runCatching { goAsync() }.getOrNull(), "glasses-acl") {
            if (!matches(address, pairedAddress(app))) return@run
            PcLog.i(TAG, "glasses_nearby acl")
            submit(app)
        }
    }

    companion object {
        private const val TAG = "PcAutoStop"

        /** Only the paired glasses: both addresses known and equal (case-insensitive). No stored address (not learned yet) matches nothing. */
        internal fun matches(seen: String?, paired: String?): Boolean = !seen.isNullOrBlank() && !paired.isNullOrBlank() && seen.equals(paired, ignoreCase = true)

        /** The stored address of the paired glasses, only while a pairing exists; a test seam. */
        @Volatile internal var pairedAddress: (Context) -> String? = { app -> if (CxrGlassesLink.isPaired(app)) CxrGlassesLink.pairedAddress(app) else null }

        /** The event goes to the process-wide engine; a test seam. */
        @Volatile internal var submit: (Context) -> Unit = { app -> ReceiverWork.await(AutoStopWake.engine(app), AutoStopEvent.GlassesNearby()) }

        /** Rokid glasses show up in the bonded list under a name that contains this. */
        private const val NAME_HINT = "rokid"

        /**
         * Learns the glasses' address while the link is up: the ONE bonded device whose name says Rokid. The CXR SDK (client-l 1.1.2) exposes no address of the session's device
         * (SessionConfig, GlassesInfo and the link carry none), so the name is the only identity available. A different single device REPLACES a stored address; none or several (or a
         * stored address that is no longer bonded) = nothing is learned AND the stored address is forgotten (nothing then matches); a pairing change forgets it too
         * ([CxrGlassesLink.forgetAddress]). Off the main thread, needs BLUETOOTH_CONNECT. Display names and addresses never leave this function.
         */
        fun learn(context: Context) {
            val app = context.applicationContext
            runCatching {
                if (!com.rokid.context.phone.context.CarSource.hasBluetoothPermission(app)) return
                val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
                @Suppress("MissingPermission") // checked above
                val bonded = adapter.bondedDevices.orEmpty().map { it.address to runCatching { it.name }.getOrNull() }
                when (val a = pick(bonded)) {
                    null -> CxrGlassesLink.forgetAddress(app)
                    else -> if (!a.equals(CxrGlassesLink.pairedAddress(app), ignoreCase = true)) CxrGlassesLink.rememberAddress(app, a)
                }
            }
        }

        internal fun pick(bonded: List<Pair<String, String?>>): String? =
            bonded.filter { (_, name) -> name?.contains(NAME_HINT, ignoreCase = true) == true }.singleOrNull()?.first
    }
}
