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
        DiagProbe(app, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), onTimeChange = { n++ }).registerTime()
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIMEZONE_CHANGED))
        app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_TIME_CHANGED))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(2, n)
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
