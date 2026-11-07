package com.rokid.context.phone.hub

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.rokid.context.phone.voice.VoiceGraph

/**
 * Starts the hub whenever one of our activities resumes (a connectedDevice FGS may only start from the foreground),
 * gives an OFFLINE link one reconnect, and re-checks voice. Lives in ContextApp so it survives Phase 4's MainActivity
 * rewrite.
 */
object HubActivityHook : Application.ActivityLifecycleCallbacks by HubResumeHook(
    voiceForeground = { activity, resume -> VoiceGraph.get(activity).onForeground(resume) },
    // Leaving the front stops the hub unless something needs it ([BackgroundPolicy.hubNeeded]): the glasses link on and paired, a context source, an ask in progress.
    keepRunning = { activity -> BackgroundInputs.hubNeeded(activity.applicationContext) },
    positive = { activity -> if (HubGraph.isBuilt) HubGraph.get(activity).positiveSignal() },
    stopHub = { activity -> ContextHubService.stop(activity.applicationContext) },
    front = { HubGraph.noteFront() },
)

/**
 * [voiceForeground] runs on every resume (fix round 1): on a cold process the service's onCreate comes after this
 * resume, and without BLUETOOTH_CONNECT the hub never runs, but the voice gate and probe must still be re-checked while
 * the app is in front. It also takes the mic FGS type when the service runs (preflight F12: the only refresh call).
 * Each resume gets the next number, so the service retries a refused mic type at most once per resume (fix round 2).
 */
/**
 * Nearby devices: with "Keep running when nearby" off ([keepRunning] false), the last of our activities leaving the
 * front stops the hub ([stopHub]), even while the glasses are connected. On (the default) it keeps running.
 */
class HubResumeHook(
    private val voiceForeground: (Activity, Long) -> Unit,
    private val keepRunning: (Activity) -> Boolean = { true },
    private val stopHub: (Activity) -> Unit = {},
    /** The app came to the front: a positive signal for the back-off, even when no hub service runs. */
    private val positive: (Activity) -> Unit = {},
    /** AutoStop: the app came to the front (resume) or finally left it (stopped): the front hold of [BackgroundPolicy.FRONT_HOLD_MS] counts from the last of these. */
    private val front: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks {
    private var resumes = 0L
    private var started = 0

    override fun onActivityResumed(activity: Activity) {
        val resume = ++resumes
        positive(activity)
        front()
        ContextHubService.ensureRunning(activity, resume, force = true) // in front the hub runs as before; leaving the front stops it unless something needs it
        if (ContextHubService.isRunning()) HubGraph.get(activity).onAppResumed()
        voiceForeground(activity, resume)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        // A configuration change stops and restarts the activity: it is not the app leaving the front.
        if (started == 0 && !activity.isChangingConfigurations) front()
        if (started == 0 && !activity.isChangingConfigurations && !keepRunning(activity)) stopHub(activity)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
