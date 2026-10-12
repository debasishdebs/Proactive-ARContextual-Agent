package com.rokid.context.phone.ui.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * BLUETOOTH_CONNECT is a runtime permission from API 31 (S); Phase 3's glasses hub cannot start without it.
 * The app never blocks on it: when it is missing the shell shows [HINT] and everything else works.
 */
object BluetoothPermission {
    const val PERMISSION = Manifest.permission.BLUETOOTH_CONNECT
    const val HINT = "Bluetooth permission is off, so your glasses can't connect."

    fun isMissing(sdkInt: Int, granted: Boolean): Boolean = sdkInt >= Build.VERSION_CODES.S && !granted

    fun isMissing(context: Context): Boolean =
        isMissing(Build.VERSION.SDK_INT, ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED)
}
