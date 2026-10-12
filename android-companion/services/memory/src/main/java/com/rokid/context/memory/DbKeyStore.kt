package com.rokid.context.memory

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Random 32-byte SQLCipher passphrase, created once and kept in EncryptedSharedPreferences. */
object DbKeyStore {
    private const val PREFS = "db_key"
    private const val KEY = "passphrase"

    fun passphrase(context: Context): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            context, PREFS, MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()
        return fresh
    }
}
