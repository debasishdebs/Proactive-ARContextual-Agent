package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.toProviderSettings

/**
 * Seeds the endpoint registry from the old single-provider config (spec §4.1 seed), then retires the old key.
 * Reads the Keystore-backed secrets, so it runs off Main only (ContextApp's `ai-seed` thread).
 */
object LegacyKeySeed {
    suspend fun run(context: Context, seeder: EndpointSeeder): Result<Unit> {
        val settings = AppConfigStore.get(context).toProviderSettings()
        return seeder.seedIfNeeded(settings).onSuccess {
            // F23: an empty read (Keystore unavailable) or a seed done earlier without the key must not delete the only copy.
            if (seeder.legacyKeyInVault(settings)) AppConfigStore.clearLegacySecret(context)
        }
    }
}
