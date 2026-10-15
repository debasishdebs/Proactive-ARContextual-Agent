package com.rokid.context.glasses

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fix wave H4: last-alive heartbeat, "restart gap=<s> reason=<...>" at process start, and the Rokid-closed counter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestartDiagnosticsTest {
    private val logs = ArrayList<String>()
    private var wall = 1_000_000_000L
    private var installedAt = 0L
    private var userRequestedExit: Boolean? = null

    private fun diag(name: String) = RestartDiagnostics(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() },
        wallClock = { wall }, installedAtMs = { installedAt }, lastExitWasUserRequested = { userRequestedExit }, log = { logs += it },
    )


    @Test fun firstEverStartIsUnknownWithNoGap() {
        val d = diag("rd1")
        d.onProcessStart()
        assertEquals(listOf("restart gap=-1 reason=unknown ui_mode=home"), logs)
        assertEquals(0, d.closes); assertNull(d.lastGapSec)
    }

    @Test fun forceStopAfterAHeartbeatCountsAsRokidClosingTheApp() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd2", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, { userRequestedExit }, { logs += it }, uiMode = { UiModeStore.FULL })
        mk().heartbeat()
        wall += 38_400; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart gap=38 reason=forcestop ui_mode=full", logs.last())
        assertEquals(1, d.closes); assertEquals(38, d.lastGapSec)
        assertEquals("Rokid closed the app 1 time (last gap 38s)", d.summary())
        wall += 120_000; mk().heartbeat(); wall += 5_000
        val d2 = mk(); d2.onProcessStart()
        assertEquals(2, d2.closes); assertEquals("Rokid closed the app 2 times (last gap 5s)", d2.summary())
    }

    @Test fun anInstallAfterTheLastHeartbeatIsNotRokidClosingIt() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd3", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, { userRequestedExit }, { logs += it }, uiMode = { UiModeStore.FULL })
        mk().heartbeat()
        wall += 60_000; installedAt = wall - 10_000; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart gap=60 reason=install ui_mode=full", logs.last())
        assertEquals(0, d.closes); assertNull(d.summary())
    }

    @Test fun otherExitsAreUnknownAndLogsAreCountOnly() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd4", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, { userRequestedExit }, { logs += it }, uiMode = { UiModeStore.FULL })
        mk().heartbeat(); wall += 90_000; userRequestedExit = false
        mk().onProcessStart()
        assertEquals("restart gap=90 reason=unknown ui_mode=full", logs.last())
        assertEquals(0, mk().closes)
    }

    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    /** Fix wave I (C1): a kill while the app was meant to be running (icon OR full) comes back minimised, once; never from home. */
    @Test fun aKillInIconOrFullModeRestartsMinimisedOnceButNeverFromHome() {
        val prefs = prefs("rd5")
        val modes = UiModeStore(prefs)
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, { userRequestedExit }, { logs += it }, uiMode = { modes.get() })
        modes.set(UiModeStore.ICON); mk().heartbeat(); wall += 20_000; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart gap=20 reason=forcestop ui_mode=icon", logs.last())
        assertEquals(true, d.takeStartMinimised()); assertEquals(false, d.takeStartMinimised())
        modes.set(UiModeStore.FULL); mk().heartbeat(); wall += 1_000; userRequestedExit = true
        val d2 = mk(); d2.onProcessStart()
        assertEquals(true, d2.takeStartMinimised(), "a full-screen kill comes back as the icon, never over a call or a scene")
        modes.set(UiModeStore.FULL); mk().heartbeat(); wall += 1_000; userRequestedExit = false
        assertEquals(true, mk().also { it.onProcessStart() }.takeStartMinimised(), "any death while running, not only a user-requested exit")
        modes.set(UiModeStore.HOME); mk().onExit(); wall += 1_000
        assertEquals(false, mk().also { it.onProcessStart() }.takeStartMinimised(), "the wearer had exited: a plain start")
        installedAt = wall + 10; modes.set(UiModeStore.FULL); wall += 100_000; mk().heartbeat(); installedAt = wall + 1_000; wall += 2_000
        assertEquals(false, mk().also { it.onProcessStart() }.takeStartMinimised(), "an update is a plain start")
    }

    /** Fix wave I: no heartbeat while closed, an exit forgets the stamp (no gap, no count), the exit stamp is kept for the hello. */
    @Test fun aDeliberateCloseLeavesNoGapAndNoCount() {
        val prefs = prefs("rd6")
        val modes = UiModeStore(prefs)
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, { userRequestedExit }, { logs += it }, uiMode = { modes.get() })
        modes.set(UiModeStore.FULL); mk().heartbeat()
        modes.set(UiModeStore.HOME); val d = mk(); d.onUserExit(wall); wall += 3_600_000
        d.heartbeat() // the runtime's timer keeps ticking after the exit
        assertEquals(wall - 3_600_000, d.userExitAtMs)
        userRequestedExit = true
        val d2 = mk(); d2.onProcessStart()
        assertEquals("restart gap=-1 reason=unknown ui_mode=home", logs.last())
        assertEquals(0, d2.closes); assertEquals(false, d2.takeStartMinimised())
    }
}
