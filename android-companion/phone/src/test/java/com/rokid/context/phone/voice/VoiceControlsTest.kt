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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Preflight F2: the probe runs only from the foreground, never because the stored language loaded or changed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class VoiceControlsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = ctx.getSharedPreferences("voice_controls_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    private val platform = object : VoicePlatform {
        override val sdk = 37
        var probes = 0
        var packQueries = 0
        override fun onDeviceAvailable() = true
        override fun micGranted() = true
        override suspend fun installedPacks(): Set<String> { packQueries++; return setOf("en-US", "hi-IN") }
        override suspend fun runProbe(localeTag: String): ProbeOutcome { probes++; return ProbeOutcome(166_142, 166_142, capacityBytes = 51_200) }
        override fun fingerprint() = "build-1"
    }

    private fun TestScope.parts(): Triple<VoiceSettingsStore, VoiceCapabilityService, MutableList<String>> {
        val work = StandardTestDispatcher(testScheduler)
        val store = VoiceSettingsStore({ prefs }, this, work)
        val caps = VoiceCapabilityService(platform, InMemoryProbeRecords(), { store.settings.value.language }, this, work)
        return Triple(store, caps, mutableListOf())
    }

    @Test fun aStoredLanguageLoadingAfterABackgroundStartRefreshesWithoutAProbe() = runTest {
        prefs.edit().putString("language", "HINDI").commit()
        val (store, caps, _) = parts()
        watchLanguage(this, store.settings, caps)
        advanceUntilIdle()
        assertEquals(SpeechLanguage.HINDI, store.settings.value.language)
        assertEquals(0, platform.probes)
        assertEquals("the new language's pack is re-read", true, platform.packQueries > 0)
        coroutineContext.cancelChildrenSafely()
    }

    @Test fun choosingALanguageInSettingsProbesOnceItIsStored() = runTest {
        val (store, caps, events) = parts()
        watchLanguage(this, store.settings, caps)
        val controls = StoreVoiceControls(
            store, caps, scope = this,
            onForeground = { events += "foreground ${store.settings.value.language}"; caps.refresh(allowProbe = true) },
            download = { PackDownload.DONE }, openSettings = {},
        )
        advanceUntilIdle()
        controls.setLanguage(SpeechLanguage.HINDI)
        advanceUntilIdle()
        assertEquals(listOf("foreground HINDI"), events)
        assertEquals(1, platform.probes)
        coroutineContext.cancelChildrenSafely()
    }

    @Test fun aPackDownloadRechecksInTheForeground() = runTest {
        val (store, caps, events) = parts()
        var tag: String? = null
        val controls = StoreVoiceControls(store, caps, scope = this, onForeground = { events += "foreground" }, download = { tag = it; PackDownload.SCHEDULED }, openSettings = {})
        advanceUntilIdle()
        assertEquals(PackDownload.SCHEDULED, controls.downloadPack())
        assertEquals("en-US", tag)
        assertEquals(listOf("foreground"), events)
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildrenSafely() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
}
