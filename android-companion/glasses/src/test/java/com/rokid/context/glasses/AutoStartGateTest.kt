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
}
