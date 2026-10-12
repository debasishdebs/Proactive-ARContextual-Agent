package com.rokid.context.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

interface KeyVault {
    fun get(ref: String): String?
    fun put(ref: String, key: String)
    fun remove(ref: String)
}

class EncryptedKeyVault(context: Context) : KeyVault {
    private val prefs = EncryptedSharedPreferences.create(
        context, "endpoint_keys", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun get(ref: String) = prefs.getString(ref, null)
    override fun put(ref: String, key: String) { prefs.edit().putString(ref, key).apply() }
    override fun remove(ref: String) { prefs.edit().remove(ref).apply() }
}

class InMemoryKeyVault : KeyVault {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(ref: String) = map[ref]
    override fun put(ref: String, key: String) { map[ref] = key }
    override fun remove(ref: String) { map.remove(ref) }
}
