package com.rokid.context

import androidx.test.core.app.ApplicationProvider
import com.rokid.context.ai.EnvDefaults
import com.rokid.context.data.runtime.AppConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No API key or other secret may be compiled into the APK; keys live only in the KeyVault. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NoSecretsInBuildConfigTest {
    private val secretName = Regex("(?i)(KEY|SECRET|TOKEN|PASSWORD|PASSWD|CREDENTIAL)")

    @Test fun buildConfigHasNoSecretFields() {
        val offenders = BuildConfig::class.java.declaredFields.map { it.name }.filter { secretName.containsMatchIn(it) }
        assertTrue("BuildConfig must not carry secrets: $offenders", offenders.isEmpty())
    }

    @Test fun unsetApiKeyIsBlank() {
        EnvDefaults.init("https://az.example", "gpt-x", "http://o:11434", "llama", "ollama_only", "/m.gguf")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("", AppConfigStore.get(ctx).apiKey)
    }
}
