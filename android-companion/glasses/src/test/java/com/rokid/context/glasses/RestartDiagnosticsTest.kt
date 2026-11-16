package com.rokid.context.glasses

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fix wave H4: last-alive heartbeat, "restart rokid=N unknown=M other=K gap=<s> reason=<...>" at process start, and the Rokid-closed counter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestartDiagnosticsTest {
    private val logs = ArrayList<String>()
    private var wall = 1_000_000_000L
    private var installedAt = 0L
    private var userRequestedExit: Boolean? = null
    private var kind: ExitKind? = ExitKind.ROKID
    private var exitAt = 0L
    private fun exit() = userRequestedExit?.let { ExitFacts(it, "force stop from pid 4242", exitAt) }

    private fun diag(name: String) = RestartDiagnostics(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() },
        wallClock = { wall }, installedAtMs = { installedAt }, lastExit = ::exit, log = { logs += it }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null },
    )


    @Test fun firstEverStartIsUnknownWithNoGap() {
        val d = diag("rd1")
        d.onProcessStart()
        assertEquals(listOf("restart rokid=0 unknown=0 other=0 gap=-1 reason=unknown ui_mode=home"), logs)
        assertEquals(0, d.closes); assertNull(d.lastGapSec)
    }

    @Test fun forceStopAfterAHeartbeatCountsAsRokidClosingTheApp() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd2", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { UiModeStore.FULL }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null })
        mk().heartbeat()
        wall += 38_400; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart rokid=1 unknown=0 other=0 gap=38 reason=forcestop_rokid ui_mode=full", logs.last())
        assertEquals(1, d.closes); assertEquals(38, d.lastGapSec)
        assertEquals("Rokid closed the app 1 time (last gap 38s)", d.summary())
        wall += 120_000; mk().heartbeat(); wall += 5_000
        val d2 = mk(); d2.onProcessStart()
        assertEquals(2, d2.closes); assertEquals("Rokid closed the app 2 times (last gap 5s)", d2.summary())
    }

    @Test fun anInstallAfterTheLastHeartbeatIsNotRokidClosingIt() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd3", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { UiModeStore.FULL }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null })
        mk().heartbeat()
        wall += 60_000; installedAt = wall - 10_000; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart rokid=0 unknown=0 other=0 gap=60 reason=install ui_mode=full", logs.last())
        assertEquals(0, d.closes); assertNull(d.summary())
    }

    @Test fun otherExitsAreUnknownAndLogsAreCountOnly() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd4", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { UiModeStore.FULL }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null })
        mk().heartbeat(); wall += 90_000; userRequestedExit = false
        mk().onProcessStart()
        assertEquals("restart rokid=0 unknown=0 other=0 gap=90 reason=unknown ui_mode=full", logs.last())
        assertEquals(0, mk().closes)
    }

    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    /** Fix wave I (C1): a kill while the app was meant to be running (icon OR full) comes back minimised, once; never from home. */
    @Test fun aKillInIconOrFullModeRestartsMinimisedOnceButNeverFromHome() {
        val prefs = prefs("rd5")
        val modes = UiModeStore(prefs)
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { modes.get() }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null })
        modes.set(UiModeStore.ICON); mk().heartbeat(); wall += 20_000; userRequestedExit = true
        val d = mk(); d.onProcessStart()
        assertEquals("restart rokid=1 unknown=0 other=0 gap=20 reason=forcestop_rokid ui_mode=icon", logs.last())
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
        fun mk() = RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { modes.get() }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null })
        modes.set(UiModeStore.FULL); mk().heartbeat()
        modes.set(UiModeStore.HOME); val d = mk(); d.onUserExit(wall); wall += 3_600_000
        d.heartbeat() // the runtime's timer keeps ticking after the exit
        assertEquals(wall - 3_600_000, d.userExitAtMs)
        d.clearUserExit(); assertNull(d.userExitAtMs) // the wearer opened it again (fix wave I N3)
        d.onUserExit(5)
        userRequestedExit = true
        val d2 = mk(); d2.onProcessStart()
        assertEquals("restart rokid=0 unknown=0 other=0 gap=-1 reason=unknown ui_mode=home", logs.last())
        assertEquals(0, d2.closes); assertEquals(false, d2.takeStartMinimised())
    }

    private fun cumulative(name: String): Pair<android.content.SharedPreferences, () -> RestartDiagnostics> {
        val prefs = prefs(name)
        return prefs to { RestartDiagnostics(prefs, { wall }, { installedAt }, ::exit, { logs += it }, uiMode = { UiModeStore.FULL }, classify = { f, _, _ -> if (f.userRequested) kind?.let { Classified(it) } else null }) }
    }

    @Test fun otherForceStopNotCountedAsRokid() {
        val (_, mk) = cumulative("rd7")
        mk().heartbeat(); wall += 30_000; userRequestedExit = true; kind = ExitKind.OTHER
        val d = mk(); d.onProcessStart()
        assertEquals(0, d.closes); assertEquals(1, d.otherCloses); assertEquals(0, d.unverifiedCloses)
        assertEquals("restart rokid=0 unknown=0 other=1 gap=30 reason=forcestop_other ui_mode=full", logs.last())
        assertNull(d.lastGapSec, "the gap of a non-Rokid stop is not stored")
        assertEquals(true, d.takeStartMinimised(), "any force-stop while running still restarts in icon mode")
    }

    @Test fun unknownNotAddedToRokid() {
        val (_, mk) = cumulative("rd8")
        mk().heartbeat(); wall += 30_000; userRequestedExit = true; kind = ExitKind.UNKNOWN
        val d = mk(); d.onProcessStart()
        assertEquals(0, d.closes); assertEquals(1, d.unverifiedCloses); assertEquals(0, d.otherCloses)
        assertEquals("restart rokid=0 unknown=1 other=0 gap=30 reason=forcestop_unverified ui_mode=full", logs.last())
    }

    @Test fun gapUsesExitTimestamp() {
        val (_, mk) = cumulative("rd9")
        mk().heartbeat(); wall += 30_000; userRequestedExit = true; kind = ExitKind.ROKID; exitAt = wall - 8_000
        val d = mk(); d.onProcessStart()
        assertEquals(8, d.lastGapSec)
    }

    @Test fun gapFallsBackToHeartbeat() {
        val (_, mk) = cumulative("rd10")
        mk().heartbeat(); wall += 30_000; userRequestedExit = true; kind = ExitKind.ROKID; exitAt = 0L // before the heartbeat
        val d = mk(); d.onProcessStart()
        assertEquals(30, d.lastGapSec)
    }

    @Test fun summaryMentionsUnverifiedAndOtherCounts() {
        val (_, mk) = cumulative("rd11")
        fun kill(k: ExitKind) { mk().heartbeat(); wall += 10_000; userRequestedExit = true; kind = k; mk().onProcessStart(); wall += 1_000 }
        kill(ExitKind.ROKID); kill(ExitKind.UNKNOWN); kill(ExitKind.OTHER); kill(ExitKind.OTHER)
        assertEquals("Rokid closed the app 1 time (last gap 10s; 1 unverified; 2 other force-stops)", mk().summary())
    }

    @Test fun summaryWhenOnlyOtherStops() {
        val (_, mk) = cumulative("rd12")
        mk().heartbeat(); wall += 10_000; userRequestedExit = true; kind = ExitKind.OTHER; mk().onProcessStart()
        assertEquals("Rokid closed the app 0 times (1 other force-stop)", mk().summary())
    }

    @Test fun restartCountsLineWrittenAndHasNoDescription() {
        val (_, mk) = cumulative("rd13")
        mk().heartbeat(); wall += 10_000; userRequestedExit = true; kind = ExitKind.ROKID
        mk().onProcessStart()
        val line = logs.last()
        assertEquals(true, line.startsWith("restart rokid=1 unknown=0 other=0 gap="), line)
        assertEquals(false, line.contains("pid") || line.contains("4242"))
    }

    /** M9: a pre-wave-3 `rokid_closes` counted every force-stop; it is reset once on the first start of the new version. */
    @Test fun legacyRokidClosesResetOnceOnUpgrade() {
        val (prefs, mk) = cumulative("rd14")
        prefs.edit().putInt("rokid_closes", 5).putInt("last_gap_sec", 12).commit()
        mk().heartbeat(); wall += 10_000; userRequestedExit = true; kind = ExitKind.ROKID
        val d = mk(); d.onProcessStart()
        assertEquals(1, d.closes, "the old 5 is gone; only this new Rokid stop counts")
        mk().heartbeat(); wall += 10_000
        val d2 = mk(); d2.onProcessStart()
        assertEquals(2, d2.closes, "no second reset")
    }

    @Test fun unverifiedStartLogsSubCauseAndTheLastExitRecordsWithoutDescriptions() {
        val sp = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd_t8", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        fun mk() = RestartDiagnostics(
            sp, wallClock = { wall }, lastExit = ::exit, log = { logs += it }, uiMode = { UiModeStore.FULL }, classify = { _, _, _ -> Classified(ExitKind.UNKNOWN, UnverifiedWhy("proc_hidden", 4242)) },
            recentExits = { listOf(ExitRecord(10, 0, 100, 2048, 4096, wall - 5_000), ExitRecord(4, 0, 400, 0, 0, wall - 60_000)) },
        )
        mk().heartbeat(); wall += 30_000; userRequestedExit = true; exitAt = wall - 5_000
        mk().onProcessStart()
        assertEquals("restart rokid=0 unknown=1 other=0 gap=5 reason=forcestop_unverified ui_mode=full sub=proc_hidden pid=4242", logs[logs.size - 2])
        assertEquals("exits n=2 [reason=10 status=0 imp=100 pssKB=2048 rssKB=4096 ago=5s] [reason=4 status=0 imp=400 pssKB=0 rssKB=0 ago=60s]", logs.last())
        assertEquals(false, logs.any { it.contains("4242 ") || it.contains("force stop") }, "no description text")
    }

    /** v1.1 T8 review (P2): the extra exit-record read is off the calling thread; a blocked collector must not block onProcessStart. */
    @Test fun aBlockedExitRecordCollectorDoesNotBlockTheCaller() {
        val sp = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rd_t8b", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        fun mk() = RestartDiagnostics(
            sp, wallClock = { wall }, lastExit = ::exit, log = { seen += it; if (it.startsWith("exits")) done.countDown() }, uiMode = { UiModeStore.FULL },
            recentExits = { release.await(); listOf(ExitRecord(10, 0, 100, 1, 2, wall - 1_000)) },
            background = { work -> Thread { work() }.also { it.isDaemon = true }.start() },
        )
        mk().heartbeat(); wall += 30_000
        val started = System.nanoTime()
        mk().onProcessStart() // returns although the collector is still blocked
        assertEquals(true, (System.nanoTime() - started) < 2_000_000_000L)
        assertEquals(true, seen.single().startsWith("restart "), "only the restart line so far")
        release.countDown(); assertEquals(true, done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(true, seen.last().startsWith("exits n=1"))
    }

    @Test fun formatters() {
        assertEquals("exits n=0", formatExits(emptyList(), 1L))
        assertEquals("trim level=15 usedMB=88 ui_mode=icon", formatTrim(15, 88, "icon"))
        assertEquals("mem usedMB=88 ui_mode=icon", formatMem(88, "icon"))
    }
}
