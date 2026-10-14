package com.rokid.context.phone.hub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.phone.ui.status.CapturePauses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground (connectedDevice, + microphone once RECORD_AUDIO is granted) service that keeps the hub — glasses link,
 * frame ingest, voice, `StateFrame` broadcast — alive (spec §1.7). Adapted from Live Fit `LiveFitHubService`. Started
 * from the front (a resumed activity, [HubActivityHook]), it takes the microphone type, which lets the on-device
 * recognizer take glasses audio while the phone is locked. A sticky restart starts in the background without it, and
 * [micHeld] stays false (glasses asks blocked) until the next resume re-enters with it.
 *
 * Glasses as a nearby device: this is also the keep-alive that lets the phone app work in the background while the
 * glasses are linked (CONNECTED, or MINIMISED on the background link). START_STICKY and not stopped with the task, so
 * backgrounding or swiping the phone app keeps the process and the link, unless Settings > Rokid Glasses > Nearby
 * devices "Keep running when nearby" is off ([KeepRunningNearby]): then leaving the app stops it ([HubResumeHook]). The
 * low-importance notification reads "Connected to glasses" while linked; otherwise it shows the link state as before.
 */
class ContextHubService : Service() {
    private var watcher: Job? = null
    /** The types the service is in the foreground with now (null before the first start). */
    private var currentTypes: Int? = null
    private val micRetry = MicRetry()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Glasses link", NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * The first command enters the foreground and starts the hub. Whether it came from the front travels in its intent
     * ([startedInFront]); a sticky restart delivers a null intent, so it is in the background. Later refresh commands
     * come only from a resumed activity (VoiceGraph.onForeground) and re-enter only when the types change.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resume = intent?.takeIf { it.hasExtra(EXTRA_RESUME) }?.getLongExtra(EXTRA_RESUME, 0)
        if (!running) {
            // Nearby devices switch off: a sticky restart in the background does not bring the hub back.
            if (stopsOnBackgroundStart(startedInFront(intent), KeepRunningNearby.of(this).enabled())) { stopSelf(); return START_NOT_STICKY }
            if (!enterForeground(READY, micRetry.toTry(wantedTypes(startedInFront(intent)), resume))) { stopSelf(); return START_NOT_STICKY }
            running = true
            val nm = getSystemService(NotificationManager::class.java)
            val hub = HubGraph.get(this)
            hub.start()
            watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
                combine(hub.status.map(::notificationText), hub.activePauses.map { it.isNotEmpty() }) { t, p -> t to p }
                    .distinctUntilChanged()
                    .collect { (text, paused) -> lastText = text; lastPaused = paused; nm.notify(ID, notification(text, paused)) }
            }
        } else if (intent?.action == ACTION_REFRESH_TYPES) {
            val wanted = micRetry.toTry(wantedTypes(inFront = true), resume)
            if (needsReentry(currentTypes, wanted)) enterForeground(lastText, wanted)
        }
        // The notification's action, after the hub is started: through the shared pause control. Resume loads the stored
        // pauses first (after a process death activePauses may not have loaded yet) and names those ids.
        when (intent?.action) {
            ACTION_PAUSE -> CapturePauses.control(this).setEnabled(false)
            ACTION_RESUME -> CapturePauses.control(this).resumeLoaded()
        }
        return START_STICKY
    }

    private fun wantedTypes(inFront: Boolean): Int {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return foregroundTypes(Build.VERSION.SDK_INT, mic, inFront)
    }

    /** startForeground with [wanted], falling back to connectedDevice alone if the mic type is refused. */
    private fun enterForeground(text: String, wanted: Int): Boolean {
        val entered = enterWithFallback(wanted) { types ->
            runCatching { ServiceCompat.startForeground(this, ID, notification(text, lastPaused), types) }
                .onFailure { Log.w(TAG, "foreground ($types) refused: ${it.javaClass.simpleName}") }.getOrThrow()
        }
        micRetry.entered(tried = wanted, entered = entered)
        if (entered != null) { currentTypes = entered; _micHeld.value = holdsMic(entered) }
        return entered != null
    }

    override fun onDestroy() {
        // A service that never reached the foreground never started the hub: do not build one just to close it.
        _micHeld.value = false
        if (running) {
            running = false
            watcher?.cancel()
            HubGraph.get(this).stop()
        }
        super.onDestroy()
    }

    private fun notification(text: String, paused: Boolean): Notification = buildNotification(this, text, paused)

    companion object {
        const val TAG = "ContextHub"
        private const val CHANNEL = "hub"
        private const val ID = 31
        private const val READY = "Glasses link ready"
        /** Glasses as a nearby device (LiveFit's pattern): linked in front or minimised, the low-importance FGS says so. */
        internal const val CONNECTED_TEXT = "Connected to glasses"
        private const val ACTION_REFRESH_TYPES = "com.rokid.context.hub.REFRESH_FOREGROUND_TYPES"
        const val EXTRA_IN_FRONT = "com.rokid.context.hub.IN_FRONT"
        private const val EXTRA_RESUME = "com.rokid.context.hub.RESUME"
        @Volatile private var running = false
        @Volatile private var lastText = READY
        @Volatile private var lastPaused = false
        /** Notification action intents (handled in [onStartCommand], on the pause control's IO). */
        const val ACTION_PAUSE = "com.rokid.context.hub.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.rokid.context.hub.RESUME_CAPTURE"

        /** One action: "Pause capture" while running, "Resume capture" while paused (R-65). */
        internal fun buildNotification(context: Context, text: String, paused: Boolean): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            val (label, action) = if (paused) "Resume capture" to ACTION_RESUME else "Pause capture" to ACTION_PAUSE
            val tap = PendingIntent.getService(
                context, if (paused) 2 else 1, Intent(context, ContextHubService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val icon = Icon.createWithResource(context, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            return Notification.Builder(context, CHANNEL)
                .setContentTitle("Proactive Context")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(icon, label, tap).build())
                .build()
        }

        fun isRunning(): Boolean = running

        private val _micHeld = MutableStateFlow(false)
        /** True while the FGS holds the microphone type: the voice gate blocks glasses asks without it (fix round 2). */
        val micHeld: StateFlow<Boolean> = _micHeld.asStateFlow()

        /** One line for the legacy Live screen (Task 12 shows it in place of the RFCOMM bridge message). */
        fun statusLine(): String = lastText

        /** Idempotent: called on every activity resume ([resume] numbers it) and on Reconnect, so always from the front. */
        fun ensureRunning(context: Context, resume: Long? = null) {
            val granted = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!shouldStart(running, canStart(Build.VERSION.SDK_INT, granted))) return
            val start = Intent(context, ContextHubService::class.java).putExtra(EXTRA_IN_FRONT, true)
            resume?.let { start.putExtra(EXTRA_RESUME, it) }
            runCatching { context.startForegroundService(start) }
                .onFailure { Log.w(TAG, "start refused: ${it.javaClass.simpleName}") }
        }

        /** A start is in front only when its intent says so; a sticky restart's null intent is the background. */
        internal fun startedInFront(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_IN_FRONT, false) == true

        internal fun holdsMic(types: Int?): Boolean = types != null && types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0

        /**
         * Asks the running service to take the types it should have now (a mic grant made while the app is in front, or
         * the mic after a sticky restart). Call it only from the foreground; it re-enters only when the types change.
         * [resume] numbers the activity resume; null (a Settings recheck, a language change) counts as the latest one.
         */
        fun refreshForegroundTypes(context: Context, resume: Long? = null) {
            if (!running) return
            val refresh = Intent(context, ContextHubService::class.java).setAction(ACTION_REFRESH_TYPES)
            resume?.let { refresh.putExtra(EXTRA_RESUME, it) }
            runCatching { context.startService(refresh) }
                .onFailure { Log.w(TAG, "refresh refused: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, ContextHubService::class.java)) }

        /** "Keep running when nearby" off: only a start from the front runs the hub. */
        internal fun stopsOnBackgroundStart(inFront: Boolean, keepRunning: Boolean) = !inFront && !keepRunning

        internal fun canStart(sdk: Int, bluetoothGranted: Boolean) = sdk < 31 || bluetoothGranted
        internal fun shouldStart(running: Boolean, canStart: Boolean) = !running && canStart

        /**
         * FOREGROUND_SERVICE_TYPE_MICROPHONE exists from API 30 (R) and is taken only from the foreground ([inFront]).
         * From the background (a sticky restart) the service takes connectedDevice alone, on every API level: on 34+ a
         * microphone start would throw, and on 30-33 it would start without while-in-use mic access. Glasses voice then
         * waits for the next app resume, which re-enters with the mic ([refreshForegroundTypes]).
         */
        internal fun foregroundTypes(sdk: Int, micGranted: Boolean, inFront: Boolean): Int =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (sdk >= 30 && micGranted && inFront) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)

        /**
         * Enters with [wanted] through [start] (which throws when refused). A refused mic type retries once with
         * connectedDevice alone; a refused connectedDevice does not retry. Returns the types entered, or null.
         */
        internal fun enterWithFallback(wanted: Int, start: (Int) -> Unit): Int? {
            val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (runCatching { start(wanted) }.isSuccess) return wanted
            if (wanted == device) return null
            return if (runCatching { start(device) }.isSuccess) device else null
        }

        /** A resume re-enters the foreground only when the types it wants differ from the current ones. */
        internal fun needsReentry(current: Int?, wanted: Int): Boolean = current != wanted

        internal fun notificationText(s: GlassesStatus): String = UserFacingErrors.forLink(s.link)?.title ?: when (s.link) {
            LinkState.CONNECTED, LinkState.MINIMISED -> CONNECTED_TEXT
            LinkState.PAUSED_BT -> "Reconnecting to glasses"
            else -> "Connecting to glasses"
        }
    }
}

/**
 * Ruling (fix round 2): after the mic type is refused from the front, it is retried at most once per resume. A refresh
 * with no resume number (Settings recheck, language change) counts as the latest resume, so it never retries again.
 */
internal class MicRetry {
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private var resume = 0L
    private var refusedIn: Long? = null

    /** The types to try now: [wanted] without the mic if it was already refused during this resume. */
    fun toTry(wanted: Int, resume: Long?): Int {
        if (resume != null) this.resume = resume
        return if (refusedIn == this.resume) wanted and mic.inv() else wanted
    }

    /** Records a try: a mic type that did not end up held was refused during the current resume. */
    fun entered(tried: Int, entered: Int?) {
        if (tried and mic != 0 && (entered == null || entered and mic == 0)) refusedIn = resume
    }
}
