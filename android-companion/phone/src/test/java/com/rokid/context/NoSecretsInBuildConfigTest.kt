package com.rokid.context

import androidx.test.core.app.ApplicationProvider
import com.rokid.context.ai.EnvDefaults
import com.rokid.context.data.runtime.AppConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("", AppConfigStore.getWithLegacySecret(ctx).apiKey)
    }

    /** P8 D3: the value guard in phone/build.gradle.kts, read from the file so the test checks the regex the build uses. */
    private val secretValue: Regex by lazy {
        val gradle = java.io.File("build.gradle.kts").takeIf { it.exists() } ?: java.io.File("phone/build.gradle.kts")
        val line = gradle.readLines().single { it.trimStart().startsWith("val secretValue = Regex(\"") }
        Regex(line.substringAfter("Regex(\"").substringBeforeLast("\")").replace("\\\\", "\\"))
    }

    @Test fun theGradleValueGuardRejectsUserinfoAndKeyParams() {
        assertTrue(secretValue.containsMatchIn("https://u:p@h"))
        assertTrue(secretValue.containsMatchIn("https://h/?key=x"))
        assertTrue(secretValue.containsMatchIn("https://h/v1?alt=1&api_key=x"))
        assertFalse(secretValue.containsMatchIn("http://10.0.2.2:11434"))
        assertFalse(secretValue.containsMatchIn("https://res.openai.azure.com"))
    }

    /** Names only in the message, never a value. */
    @Test fun buildConfigValuesCarryNoCredentials() {
        val offenders = BuildConfig::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .filter { secretValue.containsMatchIn(it.get(null) as String) }.map { it.name }
        assertTrue("BuildConfig values must not carry credentials: $offenders", offenders.isEmpty())
    }
}
