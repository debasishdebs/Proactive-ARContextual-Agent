package com.rokid.context.phone.ui.settings.capture

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackgroundRunCardTest {
    @Test fun notExemptOffersTheButtonAndExplains() {
        val m = BackgroundRunModel.of(exempt = false, sdk = 34)
        assertFalse(m.exempt); assertEquals(BackgroundRunModel.BUTTON, m.buttonLabel); assertEquals("Run in the background", m.title)
        assertTrue("explains what it does and that nothing is automatic", "never asks on its own" in m.body && "at most once an hour" in m.body)
    }

    @Test fun exemptShowsNoButton() { val m = BackgroundRunModel.of(true, 34); assertTrue(m.exempt); assertNull(m.buttonLabel) }

    @Test fun oldAndroidNeedsNothing() { val m = BackgroundRunModel.of(false, 22); assertNull(m.buttonLabel); assertEquals(BackgroundRunModel.BODY_UNSUPPORTED, m.body) }

    @Test fun theRequestIsTheSystemDialogForThisAppOnly() {
        val i = BackgroundRunModel.requestIntent("com.rokid.context")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, i.action); assertEquals("package:com.rokid.context", i.data.toString())
    }

    @Test fun theManifestDeclaresThePermissionAndNothingStartsTheRequestByItself() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in manifest)
        val offenders = java.io.File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in it.readText() }.map { it.name }.toList()
        assertEquals("only the card builds the request", listOf("BackgroundRunCard.kt"), offenders)
    }

    @Test fun autoStopChoicesCoverTheRangeAndNever() {
        assertEquals("20 min", AutoStopCopy.label(20)); assertEquals("1 h", AutoStopCopy.label(60)); assertEquals("2 h", AutoStopCopy.label(120)); assertEquals("Never", AutoStopCopy.label(0))
        assertEquals(0, AutoStopCopy.OPTIONS.last()); assertEquals(5, AutoStopCopy.OPTIONS.first())
        assertNotNull(AutoStopCopy.options(37).indexOf(37).takeIf { it >= 0 }); assertTrue(AutoStopCopy.options(7_000).contains(120))
    }

    @Test fun theContextSourcesCardStatesThatWatchSignalsPause() {
        assertEquals("Watch signals pause while the glasses are off.", WATCH_PAUSE_NOTE)
    }
}
