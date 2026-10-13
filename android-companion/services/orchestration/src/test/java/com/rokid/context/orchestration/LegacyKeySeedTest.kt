package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.EndpointRepository
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.KeyVault
import com.rokid.context.data.runtime.AppConfig
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** F23 + C3 + fix round 1 (T21 review I2, m5): the legacy key ends up in KeyVault or nowhere, and no copy is left. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyKeySeedTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val secretFile get() = File(ctx.applicationInfo.dataDir, "shared_prefs/app_config_secret.xml")
    private val vault = InMemoryKeyVault()

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        provider("openai")
    }
    @After fun tearDown() {
        AppConfigStore.clearLegacySecret(ctx)
        MemoryGraph.resetForTests(null)
    }

    private fun provider(type: String) = ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
        .putString("provider_type", type).putString("endpoint", if (type == "ollama") "http://o.local:11434" else "https://az.example")
        .putString("deployment_or_model", "gpt").commit()

    // Robolectric has no AndroidKeyStore, so this is exactly the plaintext file an old build's fallback wrote.
    private fun legacyKey(key: String = "sk-legacy-1") = ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit().putString("api_key", key).commit()

    private fun seeder(v: KeyVault = vault, state: InMemorySeedState = InMemorySeedState()): EndpointSeeder = AiGraph(MemoryGraph.get(ctx), v, state).seeder
    private fun azureKey() = vault.get(EndpointRepository.keyRef(EndpointSeeder.AZURE_ID))

    @Test fun aSeededKeyIsClearedFromTheLegacyStore() = runBlocking {
        legacyKey()
        assertTrue(LegacyKeySeed.run(ctx, seeder()).isSuccess)
        assertEquals("sk-legacy-1", azureKey())
        assertEquals("", AppConfigStore.getWithLegacySecret(ctx).apiKey)
        assertFalse(secretFile.exists())
    }

    @Test fun anEarlierSeedWithoutTheKeyMovesItIntoTheVaultThenClears() = runBlocking {
        val state = InMemorySeedState()
        assertTrue(LegacyKeySeed.run(ctx, seeder(state = state)).isSuccess) // first start: no key could be read
        assertEquals(null, azureKey())
        legacyKey()
        assertTrue(LegacyKeySeed.run(ctx, seeder(state = state)).isSuccess) // seedIfNeeded returns early; the key still moves
        assertEquals("sk-legacy-1", azureKey())
        assertFalse(secretFile.exists())
    }

    @Test fun aKeylessProviderClearsAPlaintextKey() = runBlocking {
        provider("ollama"); legacyKey()
        assertTrue(LegacyKeySeed.run(ctx, seeder()).isSuccess)
        assertFalse(secretFile.exists())
        assertEquals(null, vault.get(EndpointRepository.keyRef(EndpointSeeder.OLLAMA_ID)))
    }

    @Test fun aBlankReadOnAKeyedKindKeepsTheFile() = runBlocking {
        ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit().putString("unreadable", "x").commit()
        assertTrue(LegacyKeySeed.run(ctx, seeder()).isSuccess)
        assertTrue(secretFile.exists())
    }

    @Test fun aVaultThatCannotStoreTheKeyKeepsTheLegacyKey() = runBlocking {
        legacyKey()
        val broken = object : KeyVault {
            override fun get(ref: String): String? = null
            override fun put(ref: String, key: String) = throw IllegalStateException("Couldn't store the API key")
            override fun remove(ref: String) = Unit
        }
        assertTrue(LegacyKeySeed.run(ctx, seeder(broken)).isFailure)
        assertEquals("sk-legacy-1", AppConfigStore.getWithLegacySecret(ctx).apiKey)
    }

    /** m5: with no legacy secrets file the Keystore is never opened, but the seed still runs from the plain config. */
    @Test fun noLegacyFileNeverReadsTheSecret() = runBlocking {
        var secretReads = 0
        val spy = object : AppConfigReader {
            override fun getWithLegacySecret(context: Context): AppConfig { secretReads++; return AppConfigStore.getWithLegacySecret(context) }
            override fun getWithoutSecret(context: Context): AppConfig = AppConfigStore.getWithoutSecret(context)
            override fun isPipelineEnabled(context: Context): Boolean = AppConfigStore.isPipelineEnabled(context)
        }
        assertTrue(LegacyKeySeed.run(ctx, seeder(), spy).isSuccess)
        assertEquals(0, secretReads)
        assertNotNull(MemoryGraph.get(ctx).db.endpointDao().byId(EndpointSeeder.AZURE_ID))
        assertFalse(secretFile.exists())
    }

    /** P8 D1: a file holding only the two Tink keyset entries carries no key, so it goes without a Keystore open. */
    @Test fun aKeysetOnlyFileIsDeletedWithoutOpeningTheKeystore() = runBlocking {
        ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit()
            .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "a")
            .putString("__androidx_security_crypto_encrypted_prefs_value_keyset__", "b").commit()
        assertFalse(AppConfigStore.legacySecretHasKey(ctx))
        val throwing = object : AppConfigReader {
            override fun getWithLegacySecret(context: Context): AppConfig = throw IllegalStateException("Keystore opened")
            override fun getWithoutSecret(context: Context): AppConfig = AppConfigStore.getWithoutSecret(context)
            override fun isPipelineEnabled(context: Context): Boolean = AppConfigStore.isPipelineEnabled(context)
        }
        assertTrue(LegacyKeySeed.run(ctx, seeder(), throwing).isSuccess)
        assertFalse(secretFile.exists())
    }

    @Test fun aFileWithAnyOtherEntryCountsAsHoldingAKey() {
        legacyKey()
        assertTrue(AppConfigStore.legacySecretHasKey(ctx))
    }

    /** P8 D1: the pre-registry gateway classes are gone. */
    @Test fun deadGatewayClassesAreGone() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val main = File(root, "services/ai/src/main")
        assertTrue(main.isDirectory)
        val names = main.walkTopDown().map { it.name }.toSet()
        assertFalse("ModelProviderResolver.kt" in names)
        assertFalse("ModelGatewayFactory.kt" in names)
    }

    /** P8 D2: a key left without its endpoint row (a crash mid-save or mid-delete) is swept after seeding. */
    @Test fun runSweepsOrphanKeysAfterSeeding() = runBlocking {
        vault.put(EndpointRepository.keyRef("gone"), "k")
        assertTrue(LegacyKeySeed.run(ctx, seeder()).isSuccess)
        assertEquals(null, vault.get(EndpointRepository.keyRef("gone")))
    }

    @Test fun aSweepFailureDoesNotFailTheSeed() = runBlocking {
        val broken = object : KeyVault by vault {
            override fun refs(): Set<String> = throw IllegalStateException("Keystore unavailable")
        }
        assertTrue(LegacyKeySeed.run(ctx, seeder(broken)).isSuccess)
    }
}
