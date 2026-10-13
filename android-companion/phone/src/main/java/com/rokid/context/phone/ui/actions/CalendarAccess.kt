package com.rokid.context.phone.ui.actions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The outcome of asking for calendar access. DENIED_FOREVER = the system will not ask again (offer app settings). */
enum class CalendarAccess { GRANTED, DENIED, DENIED_FOREVER }

/** Requests READ+WRITE_CALENDAR and opens this app's system settings. Lives outside `ui/settings` (Context rule). */
class CalendarAccessHandle internal constructor(val request: ((CalendarAccess) -> Unit) -> Unit, val openAppSettings: () -> Unit)

@Composable
fun rememberCalendarAccess(): CalendarAccessHandle {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((CalendarAccess) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val granted = r.isNotEmpty() && r.values.all { it }
        // After a denial, no rationale means the system stopped asking ("don't ask again" / two denials on API 30+).
        val forever = !granted && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALENDAR) == false
        pending?.invoke(if (granted) CalendarAccess.GRANTED else if (forever) CalendarAccess.DENIED_FOREVER else CalendarAccess.DENIED)
        pending = null
    }
    return remember(context, launcher) {
        CalendarAccessHandle(
            request = { cb -> pending = cb; launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            openAppSettings = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
