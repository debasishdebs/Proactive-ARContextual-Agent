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

/**
 * The config reads the app makes, as an interface so a test can inject a spy through a constructor (Task 16 carry).
 * Only [get] opens the Keystore-backed secrets store; everything that may run on Main uses the other two.
 */
interface AppConfigReader {
    fun getWithLegacySecret(context: Context): AppConfig
    fun getWithoutSecret(context: Context): AppConfig
    fun isPipelineEnabled(context: Context): Boolean
}

object AppConfigStore : AppConfigReader {
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

    /**
     * Also returns the legacy single-provider key, which is read only to seed the endpoint registry (LegacyKeySeed, off
     * Main). Opens the Keystore: never call it on Main.
     */
    override fun getWithLegacySecret(context: Context): AppConfig = getWithoutSecret(context).copy(apiKey = legacyApiKey(context))

    /** Everything but the API key, from plain prefs only: [getWithLegacySecret] also opens the Keystore-backed secrets (never on Main). */
    override fun getWithoutSecret(context: Context): AppConfig {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return AppConfig(
            pipelineEnabled = p.getBoolean(K_PIPELINE_ENABLED, true),
            localModeEnabled = p.getBoolean(K_LOCAL_MODE_ENABLED, true),
            encryptionEnabled = p.getBoolean(K_ENCRYPTION_ENABLED, false),
            memoryWindow = p.getString(K_MEMORY_WINDOW, "24h").orEmpty().ifBlank { "24h" },
            persona = p.getString(K_PERSONA, "Global Default").orEmpty().ifBlank { "Global Default" },
            providerType = p.getString(K_PROVIDER_TYPE, "openai").orEmpty().ifBlank { "openai" },
            endpoint = p.getString(K_ENDPOINT, EnvDefaults.azureEndpoint).orEmpty(),
            apiKey = "",
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
     * - The resume flag flip is also a commit(), so once this returns the queue is running for good (fix round 2).
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
        // Fix round 2: synchronous, like the stamp clear above, so the credited resume is durable before we return.
        p.edit().putBoolean(K_PIPELINE_ENABLED, true).commit()
    }

    /** Just the ⏸ flag ([getWithLegacySecret] also opens the secrets store). */
    override fun isPipelineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_PIPELINE_ENABLED, true)
    /** Legacy values written by older builds, read-only now (the HUD settings fall back to it; the encryption flag seeds a new DB). */
    fun glassesSilencyMode(context: Context): String {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(K_GLASSES_SILENCY_MODE, "hud")
            .orEmpty()
        return normalizeSilencyMode(raw)
    }

    private inline fun edit(
        context: Context,
        block: android.content.SharedPreferences.Editor.() -> Unit,
    ) {
        val editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        editor.block()
        editor.apply()
    }

    /** EncryptedSharedPreferences only, null when the Keystore is unavailable: never a plaintext fallback (C3). */
    private fun secrets(context: Context): android.content.SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context, SECRET_PREF, MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    /**
     * Nothing writes this key any more. An old build whose Keystore failed stored it in plaintext under the same file
     * (encrypted entries never use the plain name [K_API_KEY]), so that copy is read too: only so the seed can move it
     * into KeyVault, after which [clearLegacySecret] deletes the file.
     */
    private fun legacyApiKey(context: Context): String {
        val encrypted = secrets(context)?.let { runCatching { it.getString(K_API_KEY, null) }.getOrNull() }
        val raw = encrypted ?: context.getSharedPreferences(SECRET_PREF, Context.MODE_PRIVATE).getString(K_API_KEY, null)
        return raw.orEmpty().replace("\\s+".toRegex(), "")
    }

    /** Whether the legacy key file is on disk (checked without opening the Keystore, which would recreate it). */
    fun hasLegacySecretFile(context: Context): Boolean = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$SECRET_PREF.xml").exists()

    /** The legacy key is read once, to seed the endpoint registry; afterwards it lives in KeyVault only (F23: caller checks). */
    fun clearLegacySecret(context: Context) {
        runCatching { secrets(context)?.edit()?.remove(K_API_KEY)?.commit() }
        context.deleteSharedPreferences(SECRET_PREF) // also removes a plaintext file an old build may have written
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
