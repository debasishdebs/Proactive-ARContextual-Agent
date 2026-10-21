package com.rokid.context.phone.context

import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WatchSourceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ContextSourceSettings(ctx.getSharedPreferences("ws_${n.incrementAndGet()}", 0).also { it.edit().clear().commit() })

    private companion object { val n = AtomicInteger() }

    @After fun tearDown() { WatchMotionIntake.current = null }

    private fun src(sink: (Evidence) -> Unit = {}, rejected: () -> Unit = {}, installed: Boolean = true) =
        WatchSource(settings, { installed }, { EvidenceSink { sink(it) } }, rejected)

    @Test fun idAndAvailability() {
        assertEquals("watch_livefit", src().id)
        assertTrue(src().available()); assertFalse(src(installed = false).available())
    }

    @Test fun startWithoutPinnedDigestFails() {
        val s = src()
        assertFalse(s.start())
        assertNull(WatchMotionIntake.current)
    }

    @Test fun startPublishesIntakeAndStopClearsIt() {
        settings.liveFitCertSha256 = "ab"; settings.setEnabled("watch_livefit", true)
        val got = ArrayList<Evidence>(); val s = src(sink = { got += it })
        assertTrue(s.start())
        val i = WatchMotionIntake.current!!
        assertTrue(i.accept("com.livear.fit", { true }, FakeLiveFit(i).json()).ok)
        assertEquals(1, got.size); assertEquals(1, s.events)
        s.stop()
        assertNull(WatchMotionIntake.current)
        assertEquals("not_enabled", i.accept("com.livear.fit", { true }, FakeLiveFit(i).json(seq = 9)).reason)
    }

    @Test fun rejectsAreCountedAndEpochSeqPersisted() {
        settings.liveFitCertSha256 = "ab"; settings.setEnabled("watch_livefit", true)
        var rej = 0; val s = src(rejected = { rej++ }); s.start()
        val i = WatchMotionIntake.current!!
        i.accept("com.evil", { true }, "{}")
        assertEquals(1, rej)
        i.accept("com.livear.fit", { true }, FakeLiveFit(i).json(seq = 5))
        assertEquals(7193856231L, settings.watchEpoch); assertEquals(5L, settings.watchLastSeq)
        s.stop(); s.start() // phone-side restart of the source: a replay is stale
        assertEquals("stale_seq", WatchMotionIntake.current!!.accept("com.livear.fit", { true }, FakeLiveFit(i).json(seq = 5)).reason)
        s.stop()
    }

    @Test fun switchedOffSourceIsNotEnabled() {
        settings.liveFitCertSha256 = "ab"; settings.setEnabled("watch_livefit", false)
        val s = src(); s.start()
        assertEquals("not_enabled", WatchMotionIntake.current!!.accept("com.livear.fit", { true }, FakeLiveFit(WatchMotionIntake.current!!).json()).reason)
        s.stop()
    }

    @Test fun watchRegisteredInTheRegistryIsStartedByTheHub() = runTest {
        settings.liveFitCertSha256 = "ab"; settings.setEnabled("watch_livefit", true)
        val reg = ContextSourceRegistry(); val hub = ContextHub(backgroundScope, settings, reg, clock = { 1L })
        reg.register(WatchSource(settings, { true }, hub::sinkFor))
        hub.start(); runCurrent()
        assertEquals(SourceStatus.ON, hub.status.value["watch_livefit"])
        assertNotNull(WatchMotionIntake.current)
    }

    @Test fun hubShowsFailedWithoutPinnedDigest() = runTest {
        settings.setEnabled("watch_livefit", true)
        val hub = ContextHub(backgroundScope, settings, ContextSourceRegistry().also { it.register(WatchSource(settings, { true }, { EvidenceSink { } })) }, clock = { 1L })
        hub.start(); runCurrent()
        assertEquals(SourceStatus.FAILED, hub.status.value["watch_livefit"])
    }

    @Test fun refreshEveryTenMinutesKeepsTheStateCurrentFor40Minutes() = runTest {
        settings.liveFitCertSha256 = "ab"; settings.setEnabled("watch_livefit", true)
        val t0 = 1_793_000_000_000L
        val clock: () -> Long = { t0 + testScheduler.currentTime }
        val reg = ContextSourceRegistry()
        val hub = ContextHub(backgroundScope, settings, reg, clock = clock)
        reg.register(WatchSource(settings, { true }, hub::sinkFor, clock = clock, mono = clock))
        hub.start(); runCurrent()
        val i = WatchMotionIntake.current!!; val f = FakeLiveFit(i)
        assertTrue(f.send("WALKING").ok)
        repeat(4) {
            testScheduler.advanceTimeBy(10 * 60_000L); runCurrent()
            assertEquals(ContextState.WALKING, hub.decision.value.state)
            assertTrue(f.send("WALKING").ok)
        }
    }

    @Test fun manifestResolvesTheProviderAuthority() {
        val info = ctx.packageManager.resolveContentProvider("com.rokid.context.motionsource", 0)!!
        assertEquals("com.rokid.context.phone.context.WatchMotionProvider", info.name)
        assertTrue(info.exported)
        val q = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PROVIDERS)
        assertTrue(q.providers!!.any { it.name == "com.rokid.context.phone.context.WatchMotionProvider" })
    }

    @Test fun providerAnswersNotEnabledWithoutIntakeAndNullForOtherMethods() {
        val p = Robolectric.buildContentProvider(WatchMotionProvider::class.java).create("com.rokid.context.motionsource").get()
        assertNull(p.call("other", null, null))
        val r = p.call("motion_state", null, Bundle().apply { putString("json", "{}") })!!
        assertFalse(r.getBoolean("ok")); assertEquals("not_enabled", r.getString("reason"))
        assertNull(p.query(android.net.Uri.parse("content://com.rokid.context.motionsource"), null, null, null, null))
        assertEquals(0, p.delete(android.net.Uri.parse("content://com.rokid.context.motionsource"), null, null))
    }

    @Test fun providerWithIntakeButNoCallerIsBadCaller() {
        val i = WatchMotionIntake({ true }, { "ab" }, { 1L }, { })
        WatchMotionIntake.current = i
        val p = Robolectric.buildContentProvider(WatchMotionProvider::class.java).create("com.rokid.context.motionsource").get()
        val r = p.call("motion_state", null, Bundle().apply { putString("json", FakeLiveFit(i).json()) })!!
        assertEquals("bad_caller", r.getString("reason"))
    }

    @Test fun hexToBytes() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0x01), WatchMotionProvider.hexToBytes("AB:01"))
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0x01), WatchMotionProvider.hexToBytes("ab01"))
        assertNull(WatchMotionProvider.hexToBytes("abc")); assertNull(WatchMotionProvider.hexToBytes("zz")); assertNull(WatchMotionProvider.hexToBytes(""))
    }
}
