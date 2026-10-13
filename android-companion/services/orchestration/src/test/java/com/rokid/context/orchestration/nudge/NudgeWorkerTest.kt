package com.rokid.context.orchestration.nudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Verbosity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NudgeWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences("nudge_worker_test", Context.MODE_PRIVATE)
    private val now = 100_000_000_000L
    private var runs = 0
    private val hud = NudgeHud(null, Verbosity.MEDIUM, ZoneId.of("UTC"))

    @Before fun setUp() { prefs.edit().clear().commit() }

    private fun gate(h: NudgeHud? = hud) = NudgeRunGate(prefs, { now }, { h }) { runs++; NudgeRun.NotNeeded }

    @Test fun workerSkipsWithinThirtyMinutes() = runBlocking {
        prefs.edit().putLong(NudgeRunGate.KEY_LAST_RUN, now - 29 * 60_000L).commit()
        assertEquals(false, gate().run())
        assertEquals(0, runs)
        prefs.edit().putLong(NudgeRunGate.KEY_LAST_RUN, now - 31 * 60_000L).commit()
        assertEquals(true, gate().run())
        assertEquals(1, runs)
        assertEquals("stamped at run start", now, prefs.getLong(NudgeRunGate.KEY_LAST_RUN, 0))
    }

    @Test fun unknownHudSkipsRun() = runBlocking {
        assertEquals(false, gate(h = null).run())
        assertEquals(0, runs)
        assertEquals("not stamped: the next pass may run", 0L, prefs.getLong(NudgeRunGate.KEY_LAST_RUN, 0))
    }

    @Test fun aThrowingEngineStillCountsAsARun() = runBlocking {
        val g = NudgeRunGate(prefs, { now }, { hud }) { throw IllegalStateException("x") }
        assertEquals(true, g.run())
        assertEquals(now, prefs.getLong(NudgeRunGate.KEY_LAST_RUN, 0))
    }

    /** Fix round 1: a HUD read that throws is a skipped run (fail-closed), not a crash. */
    @Test fun aThrowingHudReadSkipsTheRun() = runBlocking {
        val g = NudgeRunGate(prefs, { now }, { throw IllegalStateException("prefs") }) { runs++; NudgeRun.NotNeeded }
        assertEquals(false, g.run())
        assertEquals(0, runs)
    }
}
