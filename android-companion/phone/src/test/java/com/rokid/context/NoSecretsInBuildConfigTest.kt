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

    /** The one reviewed exception: the street-map tile key is a public client key, embedded by design and NOT secret (phone/build.gradle.kts). Its value is still held to the value guard below and to a plain charset. */
    private val publicClientFields = setOf("MAP_TILES_KEY")

    @Test fun buildConfigHasNoSecretFields() {
        val offenders = BuildConfig::class.java.declaredFields.map { it.name }.filter { secretName.containsMatchIn(it) && it !in publicClientFields }
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
        // Fix round 1: these three in any position (query, fragment, path parameter).
        listOf("access_token", "subscription-key", "client_secret").forEach { n ->
            listOf("https://h/?$n=x", "https://h/?a=1&$n=x", "https://h/cb#$n=x", "https://h/p;$n=x", "$n=x").forEach { v ->
                assertTrue(v, secretValue.containsMatchIn(v))
            }
        }
        assertFalse(secretValue.containsMatchIn("http://10.0.2.2:11434"))
        assertFalse(secretValue.containsMatchIn("https://h/access_tokens/list"))
        assertFalse(secretValue.containsMatchIn("https://res.openai.azure.com"))
    }

    @Test fun theMapTileKeyHasAPlainCharset() {
        assertTrue(Regex("[A-Za-z0-9_-]*").matches(BuildConfig.MAP_TILES_KEY))
    }

    /** Names only in the message, never a value. */
    @Test fun buildConfigValuesCarryNoCredentials() {
        val offenders = BuildConfig::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .filter { secretValue.containsMatchIn(it.get(null) as String) }.map { it.name }
        assertTrue("BuildConfig values must not carry credentials: $offenders", offenders.isEmpty())
    }
}
