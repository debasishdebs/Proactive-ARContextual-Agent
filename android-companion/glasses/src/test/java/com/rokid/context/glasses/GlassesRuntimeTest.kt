package com.rokid.context.glasses

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Hello
import com.rokid.context.glasses.link.HudSettingsCache
import com.rokid.context.glasses.link.FakeBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.voice.PttProblem
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlassesRuntimeTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = GlassesRuntime.resetForTests()

    /** Spec §5.5: whichever of the frame write and the pause toggle runs first, a pause leaves no frame behind. */
    @Test fun aFrameRacingAPauseNeverSurvivesIt() = runBlocking {
        val runtime = GlassesRuntime.get(app)
        runtime.pauses.ensureLoaded()
        repeat(40) { i ->
            val write = async(Dispatchers.Default) { runtime.bufferCaptured("g$i", ByteArray(2_048), System.currentTimeMillis(), 90, "0", 80) }
            val toggle = async(Dispatchers.Default) { runtime.togglePause() }
            val t = toggle.await()
            write.await()
            check(t is PauseToggle.Paused) { "round $i started paused" }
            assertEquals(0, runtime.buffer.size, "round $i")
            runtime.togglePause() // resume for the next round
        }
    }

    /** The hello sent when a frame is buffered still carries the trigger that just fired; the phone needs the new one. */
    @Test fun schedulingTheNextCaptureSendsAFreshHello() {
        val runtime = GlassesRuntime.get(app)
        val bridge = FakeBridge()
        val c = HudController(runtime.scope, bridge, HudSettingsCache(app.getSharedPreferences("rt_test", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
        runtime.attach(c)
        runtime.onCaptureScheduled()
        assertEquals(1, bridge.sent.count { it.first == Channels.HELLO })
    }

    /** Minor 7: an old activity's late detach must not wipe the new activity's push-to-talk toast handler. */
    @Test fun anOldControllersDetachKeepsTheNewProblemHandler() {
        GlassesRuntime.bridgeFactory = { FakeBridge() }
        try {
            val runtime = GlassesRuntime.get(app)
            fun controller() = HudController(runtime.scope, FakeBridge(), HudSettingsCache(app.getSharedPreferences("rt_test", 0)), { Hello(glassesBatteryPct = 80, bufferSize = 0, glassesNowMs = 0, pauses = emptyList()) })
            val old = controller(); val new = controller()
            runtime.attach(old); runtime.attach(new)
            val handler: (PttProblem) -> Unit = {}
            runtime.ptt.onProblem = handler
            runtime.detach(old)
            assertSame(handler, runtime.ptt.onProblem)
            runtime.detach(new)
            assertNotSame(handler, runtime.ptt.onProblem)
        } finally {
            GlassesRuntime.bridgeFactory = { CxrBridge(it) }
        }
    }
}
