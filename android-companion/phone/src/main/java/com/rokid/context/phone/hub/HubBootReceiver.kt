package com.rokid.context.phone.hub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v1.1 T4: a reboot or an app update ends the hub with its process, yet the AutoStop snapshot says it was live. This only SUBMITS the same
 * [AutoStopEvent.ProcessStarted] a process start does (with the reason as its entry point), so a lost hub is recovered by the existing Recover branch: the persisted
 * ladder, the call guard and the guarded start decide; nothing here starts the hub or opens the glasses. The services-connectors BootReceiver (reminders) is separate.
 */
class HubBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val via = viaOf(intent.action) ?: return
        val app = context.applicationContext
        ReceiverWork.run(runCatching { goAsync() }.getOrNull(), "hub-boot") { submit(app, via) }
    }

    companion object {
        const val VIA_BOOT = "boot"
        const val VIA_UPDATE = "update"

        internal fun viaOf(action: String?): String? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> VIA_BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> VIA_UPDATE
            else -> null
        }

        /** The event goes to the process-wide engine; a test seam. */
        @Volatile internal var submit: (Context, String) -> Unit = { app, via -> ReceiverWork.await(AutoStopWake.engine(app), AutoStopEvent.ProcessStarted(via)) }
    }
}
