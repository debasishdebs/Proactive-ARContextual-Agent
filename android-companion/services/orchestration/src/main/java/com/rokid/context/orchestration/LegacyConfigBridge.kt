package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import com.rokid.context.ai.AiGraph
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.toProviderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Transitional (Phase 2-4): the legacy Config screen writes AppConfig; this mirrors provider edits into the endpoint
 * registry so the workers use the saved settings. The Config screen's Test button still goes through
 * ModelProviderResolver with the raw screen values until Phase 5. Invalid half-typed input fails and leaves the
 * last good endpoint/bindings in place (the primary endpoint is validated and written before any binding changes).
 * Deleted when Phase 5 ships the new AI settings screens.
 *
 * Called from the Config screen's UI callbacks (main thread), so it only does local work: Room + the key vault.
 * It must never make a network call (no endpoint test here).
 */
object LegacyConfigBridge {
    private const val TAG = "LegacyConfigBridge"
    private const val GENERIC = "Couldn't save the AI settings"

    /** On failure the exception message is short, human-readable and never contains the key. */
    fun sync(context: Context): Result<Unit> = runBlocking(Dispatchers.IO) {
        val settings = AppConfigStore.get(context).toProviderSettings()
        AiGraph.get(context).seeder.syncFromLegacy(settings).fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                if (it is CancellationException) throw it
                val message = userMessage(it, settings.apiKey)
                Log.i(TAG, "not synced: $message") // message only, never the throwable or the key
                Result.failure(IllegalArgumentException(message))
            },
        )
    }

    // Only our own validators' IllegalArgumentExceptions carry text meant for the user; anything else is generic.
    private fun userMessage(error: Throwable, key: String): String {
        val m = (error as? IllegalArgumentException)?.message?.trim().orEmpty()
        val safe = m.isNotEmpty() && m.length <= 80 && (key.isBlank() || !m.contains(key))
        return if (safe) m else GENERIC
    }
}
