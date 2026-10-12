package com.rokid.context.data.runtime

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rokid.context.ai.EnvDefaults

data class AppConfig(
    val pipelineEnabled: Boolean,
    val localModeEnabled: Boolean,
    val encryptionEnabled: Boolean,
    val memoryWindow: String,
    val persona: String,
    val providerType: String,
    val endpoint: String,
    val apiKey: String,
    val deploymentOrModel: String,
    val nativeModelPath: String,
)

object AppConfigStore {
    private const val PREF = "app_config"
    private const val SECRET_PREF = "app_config_secret"

    private const val K_PIPELINE_ENABLED = "pipeline_enabled"
    private const val K_PAUSED_AT_MS = "paused_at_ms"
    /** "Paused for an unknown time": large enough that each row's own-age cap in extendTtl decides. */
    private const val UNKNOWN_PAUSE_MS = Long.MAX_VALUE / 4
    private const val K_LOCAL_MODE_ENABLED = "local_mode_enabled"
    private const val K_ENCRYPTION_ENABLED = "encryption_enabled"
    private const val K_MEMORY_WINDOW = "memory_window"
    private const val K_PERSONA = "persona"
    private const val K_PROVIDER_TYPE = "provider_type"
    private const val K_ENDPOINT = "endpoint"
    private const val K_API_KEY = "api_key"
    private const val K_DEPLOYMENT_OR_MODEL = "deployment_or_model"
    private const val K_NATIVE_MODEL_PATH = "native_model_path"
    private const val K_GLASSES_SILENCY_MODE = "glasses_silency_mode"

    fun get(context: Context): AppConfig {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppConfig(
            pipelineEnabled = p.getBoolean(K_PIPELINE_ENABLED, true),
            localModeEnabled = p.getBoolean(K_LOCAL_MODE_ENABLED, true),
            encryptionEnabled = p.getBoolean(K_ENCRYPTION_ENABLED, false),
            memoryWindow = p.getString(K_MEMORY_WINDOW, "24h").orEmpty().ifBlank { "24h" },
            persona = p.getString(K_PERSONA, "Global Default").orEmpty().ifBlank { "Global Default" },
            providerType = p.getString(K_PROVIDER_TYPE, "openai").orEmpty().ifBlank { "openai" },
            endpoint = p.getString(K_ENDPOINT, EnvDefaults.azureEndpoint).orEmpty(),
            apiKey = secrets(context)
                .getString(K_API_KEY, "")
                .orEmpty()
                .replace("\\s+".toRegex(), ""),
            deploymentOrModel = p.getString(K_DEPLOYMENT_OR_MODEL, EnvDefaults.azureDeployment).orEmpty().ifBlank {
                EnvDefaults.azureDeployment
            },
            nativeModelPath = p.getString(K_NATIVE_MODEL_PATH, EnvDefaults.localNativeModelPath).orEmpty(),
        )
    }

    /**
     * Carry C1: a pause freezes the queue-job TTL.
     * - Pausing (from running) first expires jobs already past their TTL, so the resume credit can't revive them (fix M2),
     *   then records when the pause started. A repeated pause keeps the first time.
     * - Resuming clears the stamp with a synchronous commit() *before* crediting the paused time back
     *   ([QueueRuntimeStore.extendTtl]), so a crash mid-resume can lose this credit but never apply it twice (fix M3).
     * - A pause without a stamp (from before the stamp existed, or a crash mid-resume) gives every live job a full TTL
     *   from resume: extendTtl caps each row at its own age (fix M1).
     */
    fun setPipelineEnabled(context: Context, enabled: Boolean, nowEpochMs: Long = System.currentTimeMillis()) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val wasEnabled = p.getBoolean(K_PIPELINE_ENABLED, true)
        if (!enabled) {
            if (wasEnabled) QueueRuntimeStore.expireOverdue(context, nowEpochMs)
            edit(context) {
                putBoolean(K_PIPELINE_ENABLED, false)
                if (wasEnabled && !p.contains(K_PAUSED_AT_MS)) putLong(K_PAUSED_AT_MS, nowEpochMs)
            }
            return
        }
        val pausedAt = p.getLong(K_PAUSED_AT_MS, -1L)
        if (pausedAt >= 0L) p.edit().remove(K_PAUSED_AT_MS).commit()
        when {
            pausedAt >= 0L -> QueueRuntimeStore.extendTtl(context, byMs = (nowEpochMs - pausedAt).coerceAtLeast(0L), nowEpochMs = nowEpochMs)
            !wasEnabled -> QueueRuntimeStore.extendTtl(context, byMs = UNKNOWN_PAUSE_MS, nowEpochMs = nowEpochMs)
        }
        edit(context) { putBoolean(K_PIPELINE_ENABLED, true) }
    }

    /** Just the ⏸ flag ([get] also opens the secrets store). */
    fun isPipelineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_PIPELINE_ENABLED, true)
    fun setLocalModeEnabled(context: Context, enabled: Boolean, syncRegistry: Boolean = true): Result<Unit> {
        edit(context) { putBoolean(K_LOCAL_MODE_ENABLED, enabled) }
        if (!syncRegistry) return Result.success(Unit)
        return com.rokid.context.orchestration.LegacyConfigBridge.sync(context)
    }
    fun setEncryptionEnabled(context: Context, enabled: Boolean) = edit(context) { putBoolean(K_ENCRYPTION_ENABLED, enabled) }
    fun setMemoryWindow(context: Context, value: String) = edit(context) { putString(K_MEMORY_WINDOW, value) }
    fun setPersona(context: Context, value: String) = edit(context) { putString(K_PERSONA, value) }
    fun setGlassesSilencyMode(context: Context, mode: String) = edit(context) { putString(K_GLASSES_SILENCY_MODE, normalizeSilencyMode(mode)) }
    fun glassesSilencyMode(context: Context): String {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_GLASSES_SILENCY_MODE, "hud")
            .orEmpty()
        return normalizeSilencyMode(raw)
    }

    fun setProvider(
        context: Context,
        providerType: String,
        endpoint: String,
        apiKey: String,
        deploymentOrModel: String,
        nativeModelPath: String,
        syncRegistry: Boolean = true,
    ): Result<Unit> {
        edit(context) {
            putString(K_PROVIDER_TYPE, providerType)
            putString(K_ENDPOINT, endpoint)
            putString(K_DEPLOYMENT_OR_MODEL, deploymentOrModel)
            putString(K_NATIVE_MODEL_PATH, nativeModelPath)
        }
        secrets(context).edit().putString(K_API_KEY, apiKey.replace("\\s+".toRegex(), "")).apply()
        if (!syncRegistry) return Result.success(Unit) // per-keystroke edits: the caller syncs once, debounced, off the main thread
        // Mirror into the endpoint registry; a failure carries a short message for the Config screen.
        return com.rokid.context.orchestration.LegacyConfigBridge.sync(context)
    }

    private inline fun edit(
        context: Context,
        block: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        val editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        editor.block()
        editor.apply()
    }

    private fun secrets(context: Context): android.content.SharedPreferences {
        return runCatching {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECRET_PREF,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Fallback to plain prefs if encrypted storage is unavailable on device build.
            context.getSharedPreferences(SECRET_PREF, Context.MODE_PRIVATE)
        }
    }

    private fun normalizeSilencyMode(mode: String): String {
        return when (mode.trim().lowercase()) {
            "stealth", "silent" -> "stealth"
            "compact", "max", "verbose_max" -> "compact"
            "hud", "medium", "verbose_medium" -> "hud"
            else -> "hud"
        }
    }
}
fun AppConfig.toProviderSettings(): com.rokid.context.ai.ProviderSettings = com.rokid.context.ai.ProviderSettings(
    providerType = providerType,
    endpoint = endpoint,
    apiKey = apiKey,
    deploymentOrModel = deploymentOrModel,
    nativeModelPath = nativeModelPath,
    localModeEnabled = localModeEnabled,
)
