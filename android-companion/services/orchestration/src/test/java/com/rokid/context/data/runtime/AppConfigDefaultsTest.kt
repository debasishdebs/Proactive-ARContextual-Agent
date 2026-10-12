package com.rokid.context.data.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.EnvDefaults
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppConfigDefaultsTest {
    @Test fun unsetPrefsFallBackToEnvDefaults() {
        EnvDefaults.init("https://az.example", "gpt-x", "http://o:11434", "llama", "ollama_only", "/m.gguf")
        val c = AppConfigStore.get(ApplicationProvider.getApplicationContext())
        assertEquals("https://az.example", c.endpoint)
        assertEquals("gpt-x", c.deploymentOrModel)
        assertEquals("/m.gguf", c.nativeModelPath)
        assertEquals(c.endpoint, c.toProviderSettings().endpoint)
    }
}
