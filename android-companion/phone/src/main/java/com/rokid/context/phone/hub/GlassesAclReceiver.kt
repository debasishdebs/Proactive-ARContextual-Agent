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
            noteSeen(address, clock())
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
                if (!com.rokid.context.phone.context.CarSource.hasBluetoothPermission(app)) { PcLog.i(TAG, "glasses_addr learn result=no_permission"); return }
                val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: run { PcLog.i(TAG, "glasses_addr learn result=no_adapter"); return }
                @Suppress("MissingPermission") // checked above
                val bonded = adapter.bondedDevices.orEmpty().map { it.address to runCatching { it.name }.getOrNull() }
                val byName = pick(bonded)
                // Fallback (trip gap 2: the name never said Rokid on the device): the ONE bonded device whose ACL connected in the minutes before this link came up.
                val a = byName ?: pickRecentAcl(bonded.map { it.first }, seenSnapshot(), clock())
                val how = if (byName != null) "name" else if (a != null) "acl_recent" else "none"
                when (a) {
                    // nothing identified now: a stored address stays while that device is still bonded (it may have been learned from an ACL connect); else it is forgotten
                    null -> CxrGlassesLink.pairedAddress(app)?.let { st -> if (bonded.none { it.first.equals(st, ignoreCase = true) }) CxrGlassesLink.forgetAddress(app) }
                    else -> if (!a.equals(CxrGlassesLink.pairedAddress(app), ignoreCase = true)) CxrGlassesLink.rememberAddress(app, a)
                }
                PcLog.i(TAG, "glasses_addr learn result=$how bonded=${bonded.size}")
            }
        }

        // ---- the ACL connects seen in this process (memory only, never logged): the learn fallback ----
        internal const val ACL_RECENT_MS = 3 * 60_000L
        private const val ACL_KEEP = 8
        @Volatile internal var clock: () -> Long = System::currentTimeMillis
        private val seen = ArrayDeque<Pair<String, Long>>()
        @Synchronized internal fun noteSeen(address: String, at: Long) { seen.addLast(address.uppercase() to at); while (seen.size > ACL_KEEP) seen.removeFirst() }
        @Synchronized internal fun seenSnapshot(): List<Pair<String, Long>> = seen.toList()
        @Synchronized internal fun resetSeenForTest() { seen.clear() }

        /** The one bonded address with an ACL connect in the last [ACL_RECENT_MS]; none or several = null (never a guess). */
        internal fun pickRecentAcl(bonded: List<String>, seen: List<Pair<String, Long>>, now: Long): String? {
            val b = bonded.map { it.uppercase() }.toSet()
            return seen.filter { (a, at) -> a.uppercase() in b && now - at in 0..ACL_RECENT_MS }.map { it.first.uppercase() }.distinct().singleOrNull()
        }

        internal fun pick(bonded: List<Pair<String, String?>>): String? =
            bonded.filter { (_, name) -> name?.contains(NAME_HINT, ignoreCase = true) == true }.singleOrNull()?.first
    }
}
