package com.rokid.context.glasses

import kotlin.test.Test
import kotlin.test.assertEquals

/** Fix wave I (C1, I3): icon mode after a kill, the wearer's own launcher open shows the HUD, the phone marker corrects a misread. */
class AutoStartGateTest {
    private var pending = false
    private var uptime = 100_000L
    private val gate = AutoStartGate(takePending = { pending.also { pending = false } }, uptimeMs = { uptime })

    @Test fun launcherClassificationNeedsMainLauncherAndALauncherReferrer() {
        val main = "android.intent.action.MAIN"; val cat = setOf("android.intent.category.LAUNCHER")
        assertEquals(StartSource.LAUNCHER, StartSources.classify(main, cat, "com.rokid.os.sprite.launcher"))
        assertEquals(StartSource.OTHER, StartSources.classify(main, cat, "com.rokid.os.sprite.assistserver"), "an appStart from the system service")
        assertEquals(StartSource.OTHER, StartSources.classify(main, cat, null))
        assertEquals(StartSource.OTHER, StartSources.classify(null, null, "x.launcher"))
    }

    @Test fun aRestartAfterAKillStartsMinimisedOnceNeverFullScreen() {
        pending = true
        assertEquals(true, gate.onStart(StartSource.OTHER, extraMinimised = false))
        assertEquals(false, gate.onStart(StartSource.OTHER, extraMinimised = false), "a later return to the front is the wearer's")
    }

    @Test fun theWearersOwnLauncherOpenAfterAKillShowsTheHud() {
        pending = true
        assertEquals(false, gate.onStart(StartSource.LAUNCHER, extraMinimised = false))
    }

    @Test fun theExtraMinimisesOnlyTheStartThatCarriedIt() {
        assertEquals(true, gate.onStart(StartSource.OTHER, extraMinimised = true))
        assertEquals(false, gate.onStart(StartSource.OTHER, extraMinimised = false), "the caller removed the extra: not re-applied")
    }

    @Test fun theAutoOpenMarkerPutsALauncherLookingStartBackIntoIconModeOnceInTime() {
        pending = true; gate.onStart(StartSource.LAUNCHER, false)
        uptime += 5_000
        assertEquals(true, gate.onAutoOpenMarker()); assertEquals(false, gate.onAutoOpenMarker())
        pending = true; gate.onStart(StartSource.LAUNCHER, false)
        uptime += AutoStartGate.MARKER_WINDOW_MS + 1
        assertEquals(false, gate.onAutoOpenMarker(), "too late: the wearer is already looking at the HUD")
        assertEquals(false, AutoStartGate({ false }, { 0L }).onAutoOpenMarker(), "no launcher start to correct")
    }

    /** Fix wave I N2: a reopen of a LIVE process (no pending kill) is minimised too, by the marker either way round. */
    @Test fun aLiveProcessReopenIsMinimisedWhateverComesFirst() {
        // start first, marker after
        assertEquals(false, gate.onStart(StartSource.OTHER, extraMinimised = false, fromMinimised = true))
        uptime += 3_000
        assertEquals(true, gate.onAutoOpenMarker())
        // marker first, start after (even one that looks like the launcher, coming from icon mode)
        uptime += 60_000
        gate.onAutoOpenMarker()
        uptime += 1_000
        assertEquals(true, gate.onStart(StartSource.LAUNCHER, false, fromMinimised = true))
        assertEquals(true, gate.onStart(StartSource.OTHER, false))
        uptime += 60_000
        assertEquals(false, gate.onStart(StartSource.LAUNCHER, false, fromMinimised = true), "long after the marker the wearer's open shows the HUD")
        assertEquals(false, AutoStartGate({ false }, { uptime }).also { it.onStart(StartSource.LAUNCHER, false) }.onAutoOpenMarker(), "the wearer's own launcher open of a live app is no candidate")
    }

    /** S21: two gates with identical state per row, because decide() and onStart() both consume the one-shot pending flag. */
    @Test fun decisionTable() {
        for (source in StartSource.values()) for (pend in listOf(false, true)) for (marker in listOf(false, true))
            for (fromMin in listOf(false, true)) for (extra in listOf(false, true)) {
                fun gate(): AutoStartGate {
                    var p = pend
                    val up = 100_000L
                    return AutoStartGate({ p.also { p = false } }, { up }).also { if (marker) it.onAutoOpenMarker() }
                }
                val want = extra || (pend && source != StartSource.LAUNCHER) || (marker && (source != StartSource.LAUNCHER || fromMin))
                val d = gate().decide(source, extra, fromMin)
                val row = "src=$source pend=$pend marker=$marker fromMin=$fromMin extra=$extra"
                assertEquals(want, d.minimise, row)
                assertEquals(d.minimise, gate().onStart(source, extra, fromMin), "onStart == decide: $row")
                assertEquals(pend, d.pending, row); assertEquals(marker, d.markerRecent, row)
                assertEquals(extra, d.extra, row); assertEquals(fromMin, d.fromMinimised, row); assertEquals(source, d.source, row)
            }
    }

    @Test fun namedRows() {
        fun d(source: StartSource, pend: Boolean = false, marker: Boolean = false, fromMin: Boolean = false, extra: Boolean = false): Boolean {
            var p = pend
            val g = AutoStartGate({ p.also { p = false } }, { 100_000L }).also { if (marker) it.onAutoOpenMarker() }
            return g.decide(source, extra, fromMin).minimise
        }
        assertEquals(false, d(StartSource.LAUNCHER, pend = true), "a wearer's launcher open after a kill shows the HUD")
        assertEquals(true, d(StartSource.OTHER, pend = true), "a CXR start after a kill minimises")
        assertEquals(true, d(StartSource.LAUNCHER, marker = true, fromMin = true), "a launcher-looking start from icon mode within the marker window")
        assertEquals(true, d(StartSource.LAUNCHER, extra = true))
        assertEquals(false, d(StartSource.LAUNCHER), "a fresh launcher open")
    }

    @Test fun refClassification() {
        assertEquals(RefClass.NONE, StartSources.refClass(null))
        assertEquals(RefClass.NONE, StartSources.refClass("  "))
        assertEquals(RefClass.LAUNCHER, StartSources.refClass("com.rokid.os.sprite.Launcher"))
        assertEquals(RefClass.OTHER, StartSources.refClass("com.rokid.os.sprite.assistserver"))
    }

    @Test fun refClassNeverLeaksPackage() {
        val pkg = "com.secretvendor.zzqx.launcher"
        val d = StartDecision(minimise = true, pending = true, markerRecent = false, extra = false, fromMinimised = true, source = StartSource.LAUNCHER)
        val line = d.line(StartSources.refClass(pkg))
        assertEquals("start src=LAUNCHER ref=LAUNCHER pending=1 marker=0 fromMin=1 extra=0 minimise=1", line)
        assertEquals(false, line.contains("secretvendor") || line.contains(pkg))
    }
}
