package com.rokid.context.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface KeyVault {
    fun get(ref: String): String?
    fun put(ref: String, key: String)
    fun remove(ref: String)
    /** Every stored ref (the orphan sweep, P8 D2). */
    fun refs(): Set<String> = emptySet()
}

/** Opens the Keystore on first use, never at construction (built on Main by `AiGraph.get`); callers use it off Main. */
class EncryptedKeyVault(context: Context) : KeyVault {
    private val app = context.applicationContext
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            app, "endpoint_keys", MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    override fun get(ref: String) = prefs.getString(ref, null)
    override fun put(ref: String, key: String) { check(prefs.edit().putString(ref, key).commit()) { "Couldn't store the API key" } }
    override fun remove(ref: String) { check(prefs.edit().remove(ref).commit()) { "Couldn't remove the API key" } }
    override fun refs(): Set<String> = prefs.all.keys.toSet()
}

class InMemoryKeyVault : KeyVault {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(ref: String) = map[ref]
    override fun put(ref: String, key: String) { map[ref] = key }
    override fun remove(ref: String) { map.remove(ref) }
    override fun refs(): Set<String> = map.keys.toSet()
}
