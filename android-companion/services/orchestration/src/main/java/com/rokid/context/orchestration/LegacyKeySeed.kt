package com.rokid.context.orchestration

import android.content.Context
import kotlinx.coroutines.CancellationException
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.SecretBakCleanup
import com.rokid.context.data.runtime.toProviderSettings

/**
 * Seeds the endpoint registry from the old single-provider config (spec §4.1 seed), then retires the old key.
 * Reads the Keystore-backed secrets, so it runs off Main only (ContextApp's `ai-seed` thread).
 */
object LegacyKeySeed {
    suspend fun run(context: Context, seeder: EndpointSeeder, config: AppConfigReader = AppConfigStore): Result<Unit> {
        // B12: restored copies of the legacy key file in files/; the seed below never reads them, so they go first.
        SecretBakCleanup.run(context)
        return seed(context, seeder, config).also { seeder.sweepOrphanKeys() } // P8 D2: keys left without a row go
    }

    private suspend fun seed(context: Context, seeder: EndpointSeeder, config: AppConfigReader): Result<Unit> {
        // Fix round 1 (m5): no legacy key file (new install, or already retired) → no Keystore open on each cold start.
        // P8 D1: a file with only the Tink keyset entries holds no key: delete it without a Keystore open.
        if (AppConfigStore.hasLegacySecretFile(context) && !AppConfigStore.legacySecretHasKey(context)) AppConfigStore.deleteKeylessLegacySecretFile(context)
        if (!AppConfigStore.hasLegacySecretFile(context)) return seeder.seedIfNeeded(config.getWithoutSecret(context).toProviderSettings())
        val settings = config.getWithLegacySecret(context).toProviderSettings()
        val seeded = seeder.seedIfNeeded(settings)
        if (seeded.isFailure) return seeded
        return try {
            // Runs also when the seed finished on an earlier start: the key still moves into KeyVault or goes (EndpointSeeder).
            if (seeder.retireLegacyKey(settings)) AppConfigStore.clearLegacySecret(context)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e // P8: mapCatching used to swallow cancellation here
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
