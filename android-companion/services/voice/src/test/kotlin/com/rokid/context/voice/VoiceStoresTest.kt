package com.rokid.context.voice

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
}
