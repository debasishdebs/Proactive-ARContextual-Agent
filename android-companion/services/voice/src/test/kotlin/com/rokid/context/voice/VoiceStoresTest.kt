package com.rokid.context.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VoiceStoresTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun probeRecordsRoundTripAndAreReadOffMain() = runBlocking {
        val main = Looper.getMainLooper().thread
        var openedOn: Thread? = null
        val records = PrefsProbeRecords({ openedOn = Thread.currentThread(); ctx.getSharedPreferences("voice_probe_test", Context.MODE_PRIVATE) }, Dispatchers.IO)
        assertNull(records.read())
        records.write(ProbeRecord("fp|en-US", ProbeVerdict.UNSUPPORTED, fromGuard = true))
        assertEquals(ProbeRecord("fp|en-US", ProbeVerdict.UNSUPPORTED, fromGuard = true), records.read())
        assertNotEquals(main, openedOn)
    }

    @Test fun settingsLoadOffMainAndUpdatesPersist() = runBlocking {
        val prefs = { ctx.getSharedPreferences("voice_settings_test", Context.MODE_PRIVATE) }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val store = VoiceSettingsStore(prefs, scope, Dispatchers.IO)
        store.update { it.copy(language = SpeechLanguage.HINDI, phoneMic = false) }.join()
        assertEquals(VoiceSettings(SpeechLanguage.HINDI, phoneMic = false), store.settings.value)
        val again = VoiceSettingsStore(prefs, scope, Dispatchers.IO)
        assertEquals(VoiceSettings(SpeechLanguage.HINDI, phoneMic = false), withTimeout(5_000) { again.settings.first { it.language == SpeechLanguage.HINDI } })
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    /** Fix round 1 (privacy ruling): callers can tell defaults from stored values; [VoiceSettingsStore.loaded] flips once read. */
    @Test fun loadedFlipsOnlyAfterTheStoredValuesAreIn() {
        val prefs = ctx.getSharedPreferences("voice_settings_loaded_test", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("phone_mic", false).commit()
        val pending = ArrayDeque<Runnable>()
        val manual = java.util.concurrent.Executor { pending += it }.asCoroutineDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val store = VoiceSettingsStore({ prefs }, scope, manual)
        assertEquals(false, store.loaded.value)
        assertEquals(true, store.settings.value.phoneMic) // the default, not yet the stored value
        while (pending.isNotEmpty()) pending.removeFirst().run()
        assertEquals(true, store.loaded.value)
        assertEquals(false, store.settings.value.phoneMic)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
}
