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
object HubActivityHook : Application.ActivityLifecycleCallbacks by HubResumeHook(voiceForeground = { VoiceGraph.get(it).onForeground() })

/**
 * [voiceForeground] runs on every resume (fix round 1): on a cold process the service's onCreate comes after this
 * resume, and without BLUETOOTH_CONNECT the hub never runs, but the voice gate and probe must still be re-checked while
 * the app is in front. It also takes the mic FGS type when the service runs (preflight F12: the only refresh call).
 */
class HubResumeHook(private val voiceForeground: (Activity) -> Unit) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        ContextHubService.ensureRunning(activity)
        if (ContextHubService.isRunning()) HubGraph.get(activity).onAppResumed()
        voiceForeground(activity)
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
