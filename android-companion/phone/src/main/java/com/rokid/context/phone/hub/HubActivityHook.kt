package com.rokid.context.phone.hub

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Starts the hub whenever one of our activities resumes (a connectedDevice FGS may only start from the foreground)
 * and gives an OFFLINE link one reconnect. Lives in ContextApp so it survives Phase 4's MainActivity rewrite.
 */
object HubActivityHook : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        ContextHubService.ensureRunning(activity)
        if (ContextHubService.isRunning()) HubGraph.get(activity).onAppResumed()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
