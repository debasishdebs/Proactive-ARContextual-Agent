package com.rokid.context.phone.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class DiagProbeTest {
    @Test fun aTimeOrZoneChangeBroadcastReachesTheDataSaverHook() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        var n = 0
        DiagProbe(app, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), onTimeChange = { n++ }, receiverHandler = android.os.Handler(android.os.Looper.getMainLooper())).registerTime()
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIME_CHANGED))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(2, n)
    }

    @Test fun theTimeReceiverRunsOffTheMainThread() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val ht = android.os.HandlerThread("test-receivers").apply { start() }
        val ran = java.util.concurrent.CountDownLatch(1)
        var onMain: Boolean? = null
        DiagProbe(app, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), onTimeChange = { onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); ran.countDown() }, receiverHandler = android.os.Handler(ht.looper)).registerTime()
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        org.robolectric.Shadows.shadowOf(ht.looper).idle()
        assertTrue("the hook ran", ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("not on the main thread", false, onMain)
        ht.quitSafely()
    }

    @Test fun foregroundIsNeverNegative_aStopBeforeAnyStartIsIgnored_andStartsAreCountedByActivity() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val probe = DiagProbe(app, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()))
        probe.start() // registers the lifecycle callbacks synchronously, before any activity
        val a = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        val b = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        fun send(what: String, act: android.app.Activity) = android.app.Application::class.java.getDeclaredMethod(what, android.app.Activity::class.java).also { it.isAccessible = true }.invoke(app, act)
        assertEquals(false, probe.isForeground())
        send("dispatchActivityStopped", a); assertEquals("a stop before any start is ignored", false, probe.isForeground())
        send("dispatchActivityStarted", a); assertEquals(true, probe.isForeground())
        send("dispatchActivityStopped", a); assertEquals(false, probe.isForeground())
        send("dispatchActivityStopped", a); assertEquals("a second stop cannot make it negative", false, probe.isForeground())
        send("dispatchActivityStarted", a); assertEquals("and the next start is seen as foreground again", true, probe.isForeground())
        send("dispatchActivityStarted", b); send("dispatchActivityStopped", a); assertEquals("a second activity keeps the app in the foreground", true, probe.isForeground())
        send("dispatchActivityStopped", b); assertEquals(false, probe.isForeground())
    }

    @Test fun pruneKeepsUnfinishedWorkAndDropsFinishedOnesAboveTheCap() {
        val m = java.util.concurrent.ConcurrentHashMap<String, String>()
        repeat(10) { m["done$it"] = "SUCCEEDED/0" }
        repeat(3) { m["run$it"] = "RUNNING/1" }
        DiagProbe.pruneWorkStates(m, 5)
        assertTrue(m.size <= 5)
        assertTrue((0 until 3).all { m.containsKey("run$it") })
    }

    @Test fun pruneDoesNothingAtOrBelowTheCapAndNeverDropsUnfinished() {
        val m = HashMap<String, String>()
        repeat(4) { m["w$it"] = "ENQUEUED/0" }
        DiagProbe.pruneWorkStates(m, 4); assertEquals(4, m.size)
        DiagProbe.pruneWorkStates(m, 2); assertEquals(4, m.size)
    }
}
