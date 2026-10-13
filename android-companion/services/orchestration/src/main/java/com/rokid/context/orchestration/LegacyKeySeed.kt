package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.toProviderSettings

/**
 * Seeds the endpoint registry from the old single-provider config (spec §4.1 seed), then retires the old key.
 * Reads the Keystore-backed secrets, so it runs off Main only (ContextApp's `ai-seed` thread).
 */
object LegacyKeySeed {
    suspend fun run(context: Context, seeder: EndpointSeeder, config: AppConfigReader = AppConfigStore): Result<Unit> {
        // Fix round 1 (m5): no legacy key file (new install, or already retired) → no Keystore open on each cold start.
        if (!AppConfigStore.hasLegacySecretFile(context)) return seeder.seedIfNeeded(config.getWithoutSecret(context).toProviderSettings())
        val settings = config.getWithLegacySecret(context).toProviderSettings()
        return seeder.seedIfNeeded(settings).mapCatching {
            // Runs also when the seed finished on an earlier start: the key still moves into KeyVault or goes (EndpointSeeder).
            if (seeder.retireLegacyKey(settings)) AppConfigStore.clearLegacySecret(context)
        }
    }
}
