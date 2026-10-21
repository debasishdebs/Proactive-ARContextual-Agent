package com.rokid.context.phone.context

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ContextSourceSettingsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = ctx.getSharedPreferences("ctx_settings_test", 0).also { it.edit().clear().commit() }
    private fun settings() = ContextSourceSettings(prefs)
    private val six = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "watch_livefit", "image_intent")

    @Test fun prefsFileNameIsCtxHubState() = assertEquals("ctx_hub_state", ContextSourceSettings.PREFS)

    @Test fun defaultsAreOffAndShadowFree() {
        val s = settings()
        six.forEach { assertFalse(it, s.enabled(it)) }
        assertTrue(s.applyToCapture)
        assertTrue(s.myCars.isEmpty())
        assertNull(s.liveFitCertSha256)
    }

    @Test fun switchesAndApplyRoundTrip() {
        val s = settings()
        s.setEnabled("phone_car", true); s.applyToCapture = false
        assertTrue(settings().enabled("phone_car"))
        assertFalse(settings().enabled("phone_steps"))
        assertFalse(settings().applyToCapture)
    }

    @Test fun myCarsAddRemove() {
        val s = settings()
        s.addMyCar("AA:BB"); s.addMyCar("CC:DD"); s.removeMyCar("AA:BB")
        assertEquals(setOf("CC:DD"), settings().myCars)
    }

    @Test fun clearPersonalKeepsApplySwitchButTurnsWatchOff() {
        val s = settings()
        s.setEnabled("watch_livefit", true); s.applyToCapture = false
        s.addMyCar("AA:BB"); s.liveFitCertSha256 = "digest"; s.setWatchSeq(epoch = 7, seq = 42)
        s.clearPersonal()
        assertFalse(s.enabled("watch_livefit")); assertFalse(s.applyToCapture)
        assertTrue(s.myCars.isEmpty()); assertNull(s.liveFitCertSha256)
        assertEquals(0L, s.watchEpoch); assertEquals(0L, s.watchLastSeq)
    }

    @Test fun wipeThenClearStillClearsPersonalWhenTheWipeThrows() {
        val s = settings()
        s.setEnabled("watch_livefit", true); s.addMyCar("AA:BB"); s.liveFitCertSha256 = "digest"
        val r = runCatching { kotlinx.coroutines.runBlocking { s.wipeThenClear { error("boom") } } }
        assertTrue(r.isFailure)
        assertTrue(s.myCars.isEmpty()); assertNull(s.liveFitCertSha256); assertFalse(s.enabled("watch_livefit"))
    }

    @Test fun watchEpochAndSeqPersist() {
        settings().setWatchSeq(epoch = 9, seq = 3)
        assertEquals(9L, settings().watchEpoch); assertEquals(3L, settings().watchLastSeq)
    }

    @Test fun changesBumpOnEveryWriteThroughAnyReference() {
        val a = settings(); val b = settings()
        val before = a.changes.value
        b.setEnabled("phone_car", true)
        assertTrue(a.changes.value > before)
        val mid = a.changes.value
        prefs.edit().putBoolean("src_phone_steps", true).commit() // a direct prefs edit
        assertTrue(a.changes.value > mid)
        assertTrue(a.enabled("phone_steps"))
    }

    @Test fun ofIsOneProcessWideInstance() {
        ContextSourceSettings.resetForTests()
        assertSame(ContextSourceSettings.of(ctx), ContextSourceSettings.of(ctx))
        ContextSourceSettings.resetForTests()
    }
}
