package com.rokid.context.phone.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.voice.InMemoryProbeRecords
import com.rokid.context.voice.ProbeOutcome
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceCapabilityService
import com.rokid.context.voice.VoicePlatform
import com.rokid.context.voice.VoiceSettingsStore
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Preflight F2: the probe runs only from the foreground, never because the stored language loaded or changed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class VoiceControlsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = ctx.getSharedPreferences("voice_controls_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private val platform = object : VoicePlatform {
        override val sdk = 37
        var probes = 0
        var packQueries = 0
        var packs = setOf("en-US", "hi-IN")
        override fun onDeviceAvailable() = true
        override fun micGranted() = true
        override suspend fun installedPacks(): Set<String> { packQueries++; return packs }
        override suspend fun runProbe(localeTag: String): ProbeOutcome { probes++; return ProbeOutcome(166_142, 166_142, capacityBytes = 51_200) }
        override fun fingerprint() = "build-1"
    }

    /** A child of the test (advanceUntilIdle drives it, unlike scope); cancelled at the end of each test. */
    private fun TestScope.child() = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job))
    private fun TestScope.store(scope: CoroutineScope, work: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) = VoiceSettingsStore({ prefs }, scope, work)
    private fun TestScope.caps(scope: CoroutineScope, store: VoiceSettingsStore) =
        VoiceCapabilityService(platform, InMemoryProbeRecords(), { store.settings.value.language }, scope, kotlinx.coroutines.flow.MutableStateFlow(true), StandardTestDispatcher(testScheduler))

    /** Runs queued work only when told: a prefs load that lands whenever the test chooses. */
    private class ManualExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending += command }
        fun runAll() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    /** The stored language lands before the watcher starts: the gate is still evaluated for it (only Hindi's pack is missing). */
    @Test fun aStoredLanguageLoadingBeforeTheWatcherStartsIsEvaluatedWithoutAProbe() = runTest {
        prefs.edit().putString("language", "HINDI").commit()
        platform.packs = setOf("en-US")
        val scope = child()
        try {
            val store = store(scope)
            val caps = caps(scope, store)
            advanceUntilIdle()
            assertEquals(SpeechLanguage.HINDI, store.settings.value.language)
            watchLanguage(scope, store.settings, caps)
            advanceUntilIdle()
            assertEquals(VoiceStatus.NEEDS_SPEECH_PACK, caps.capabilities.value.phone)
            assertEquals(0, platform.probes)
        } finally {
            scope.cancel()
        }
    }

    /** The stored language lands after the watcher's first read (a slow prefs load on a sticky restart). */
    @Test fun aStoredLanguageLoadingAfterTheFirstReadIsEvaluatedWithoutAProbe() = runTest {
        prefs.edit().putString("language", "HINDI").commit()
        platform.packs = setOf("en-US")
        val scope = child()
        val prefsLoad = ManualExecutor()
        try {
            val store = store(scope, prefsLoad.asCoroutineDispatcher())
            val caps = caps(scope, store)
            watchLanguage(scope, store.settings, caps)
            advanceUntilIdle()
            assertEquals(VoiceStatus.SUPPORTED, caps.capabilities.value.phone) // evaluated for the default, English
            prefsLoad.runAll()
            advanceUntilIdle()
            assertEquals(SpeechLanguage.HINDI, store.settings.value.language)
            assertEquals(VoiceStatus.NEEDS_SPEECH_PACK, caps.capabilities.value.phone)
            assertEquals(0, platform.probes)
        } finally {
            scope.cancel()
            prefsLoad.runAll() // a load never run would hold the cancelled scope open
        }
    }

    @Test fun choosingALanguageInSettingsProbesOnceItIsStored() = runTest {
        val scope = child()
        try {
            val store = store(scope)
            val caps = caps(scope, store)
            val events = mutableListOf<String>()
            watchLanguage(scope, store.settings, caps)
            val controls = StoreVoiceControls(
                store, caps, scope = scope,
                onForeground = { events += "foreground ${store.settings.value.language}"; caps.refresh(allowProbe = true) },
                download = { PackDownload.DONE }, openSettings = {},
            )
            advanceUntilIdle()
            controls.setLanguage(SpeechLanguage.HINDI)
            advanceUntilIdle()
            assertEquals(listOf("foreground HINDI"), events)
            assertEquals(1, platform.probes)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Fix round 1 (review I2): a download can finish after the app left the front (it may take 60 s), so it re-reads the
     * gate without a probe and never re-enters the foreground; the probe waits for the next resume.
     */
    @Test fun aPackDownloadRereadsTheGateWithoutAProbeOrAForegroundCall() = runTest {
        val scope = child()
        try {
            val store = store(scope)
            val caps = caps(scope, store)
            val events = mutableListOf<String>()
            var tag: String? = null
            val controls = StoreVoiceControls(store, caps, scope = scope, onForeground = { events += "foreground" }, download = { tag = it; PackDownload.SCHEDULED }, openSettings = {})
            advanceUntilIdle()
            val before = platform.packQueries
            assertEquals(PackDownload.SCHEDULED, controls.downloadPack())
            advanceUntilIdle()
            assertEquals("en-US", tag)
            assertEquals(emptyList<String>(), events)
            assertTrue("the gate is re-read", platform.packQueries > before)
            assertEquals(0, platform.probes)
        } finally {
            scope.cancel()
        }
    }
}
