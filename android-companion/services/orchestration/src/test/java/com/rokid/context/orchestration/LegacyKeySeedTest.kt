package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.KeyVault
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** F23 + C3: the legacy key leaves the old secrets file only once it is in KeyVault, and then no copy is left. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyKeySeedTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ctx.getSharedPreferences("app_config", Context.MODE_PRIVATE).edit()
            .putString("provider_type", "openai").putString("endpoint", "https://az.example").putString("deployment_or_model", "gpt").commit()
        // Robolectric has no AndroidKeyStore, so this is exactly the plaintext file an old build's fallback wrote.
        ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit().putString("api_key", "sk-legacy-1").commit()
    }
    @After fun tearDown() {
        AppConfigStore.clearLegacySecret(ctx)
        MemoryGraph.resetForTests(null)
    }

    private fun seeder(vault: KeyVault, state: InMemorySeedState = InMemorySeedState()): EndpointSeeder =
        AiGraph(MemoryGraph.get(ctx), vault, state).seeder

    @Test fun aSeededKeyIsClearedFromTheLegacyStore() = runBlocking {
        val vault = InMemoryKeyVault()
        assertTrue(LegacyKeySeed.run(ctx, seeder(vault)).isSuccess)
        assertEquals("sk-legacy-1", vault.get(com.rokid.context.ai.EndpointRepository.keyRef(EndpointSeeder.AZURE_ID)))
        assertEquals("", AppConfigStore.get(ctx).apiKey)
    }

    @Test fun anEarlierSeedThatStoredNoKeyKeepsTheLegacyKey() = runBlocking {
        val done = InMemorySeedState().apply { markDone() }
        assertTrue(LegacyKeySeed.run(ctx, seeder(InMemoryKeyVault(), done)).isSuccess)
        assertEquals("sk-legacy-1", AppConfigStore.get(ctx).apiKey)
    }

    @Test fun aVaultThatCannotStoreTheKeyKeepsTheLegacyKey() = runBlocking {
        val broken = object : KeyVault {
            override fun get(ref: String): String? = null
            override fun put(ref: String, key: String) = throw IllegalStateException("Couldn't store the API key")
            override fun remove(ref: String) = Unit
        }
        assertTrue(LegacyKeySeed.run(ctx, seeder(broken)).isFailure)
        assertEquals("sk-legacy-1", AppConfigStore.get(ctx).apiKey)
    }
}
