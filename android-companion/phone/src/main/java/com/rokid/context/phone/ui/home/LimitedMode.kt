package com.rokid.context.phone.ui.home

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.rokid.context.connectors.sources.SourceKind
import com.rokid.context.connectors.sources.SourcePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * G3 limited mode (v1 section 0.1 "limited-mode banners", MVP section 12.5 "If optional permissions are skipped, show persistent
 * Limited Mode banner"). Limited = a permission the app asks for was not granted: the microphone and notifications
 * (onboarding's two asks), or a data source that is on while its permission is missing (revoked later). A source that
 * is off asks for nothing, so it is not limited. The phone app has no camera permission (Capture now uses the system
 * camera app), so there is no camera check.
 */
object LimitedMode {
    const val TITLE = "Some features are limited"
    const val MIC = "Microphone"
    const val NOTIFICATIONS = "Notifications"
    const val CALENDAR = "Calendar"
    const val CONTACTS = "Contacts"
    const val LOCATION = "Location"

    fun missing(granted: (String) -> Boolean, sdk: Int, enabledSources: Set<SourceKind>): Set<String> = buildSet {
        if (!granted(Manifest.permission.RECORD_AUDIO)) add(MIC)
        if (sdk >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(NOTIFICATIONS)
        enabledSources.forEach { k ->
            val label = when (k) {
                SourceKind.CALENDAR -> CALENDAR
                SourceKind.CONTACTS -> CONTACTS
                SourceKind.LOCATION -> LOCATION
                SourceKind.NOTES -> null
            }
            if (label != null && !SourcePermissions.of(k).all(granted)) add(label)
        }
    }
}

/** What Home needs: [show] the banner; [refresh] re-reads the permissions (on resume); [dismiss] hides it. */
interface LimitedModeControl {
    val show: StateFlow<Boolean>
    suspend fun refresh()
    fun dismiss()

    companion object {
        val None: LimitedModeControl = object : LimitedModeControl {
            override val show: StateFlow<Boolean> = MutableStateFlow(false)
            override suspend fun refresh() = Unit
            override fun dismiss() = Unit
        }
    }
}

/**
 * Dismiss stores what was missing then; the banner comes back only when something else goes missing (a newly skipped
 * or revoked permission). [missing] is read off Main.
 */
class PrefsLimitedMode(private val prefs: SharedPreferences, private val missing: () -> Set<String>) : LimitedModeControl {
    private val _show = MutableStateFlow(false)
    override val show: StateFlow<Boolean> = _show.asStateFlow()
    @Volatile private var current: Set<String> = emptySet()

    override suspend fun refresh() {
        val (now, dismissed) = withContext(Dispatchers.IO) { missing() to prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() }
        current = now
        _show.value = now.isNotEmpty() && !dismissed.containsAll(now)
    }

    override fun dismiss() {
        prefs.edit().putStringSet(KEY, current.toSet()).apply()
        _show.value = false
    }

    companion object {
        const val KEY = "limited_mode_dismissed"

        /** In the restorable `hub_settings` file (a UI preference, like the Activity sort). */
        fun forApp(context: Context): PrefsLimitedMode {
            val app = context.applicationContext
            return PrefsLimitedMode(app.getSharedPreferences(com.rokid.context.phone.hub.HubSettingsStore.PREFS, Context.MODE_PRIVATE)) {
                LimitedMode.missing(
                    { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }, Build.VERSION.SDK_INT,
                    com.rokid.context.connectors.sources.SourcesGraph.get(app).settings.enabled.value,
                )
            }
        }
    }
}
