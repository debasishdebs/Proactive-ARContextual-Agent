package com.rokid.context.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/** Random 32-byte SQLCipher passphrase, created once and kept in EncryptedSharedPreferences. */
object DbKeyStore {
    private const val PREFS = "db_key"

    private val keys = DbKeys { context ->
        EncryptedSharedPreferences.create(
            context, PREFS, MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** A fresh copy on each call, so the caller may zero it once SQLCipher has it (P8 D4). Opens the Keystore: never on Main. */
    fun passphrase(context: Context): ByteArray = keys.passphrase(context)
}

/**
 * The store behind [DbKeyStore]. [prefsFactory] runs once per process (P8 D4: EncryptedSharedPreferences used to be
 * created on every call); the whole read-or-create is synchronized, so two first opens can't create two different keys.
 */
internal class DbKeys(private val prefsFactory: (Context) -> SharedPreferences) {
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun passphrase(context: Context): ByteArray {
        val p = prefs ?: prefsFactory(context.applicationContext).also { prefs = it }
        p.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Never hand out a key that was not durably stored: the DB would become unreadable next launch.
        check(p.edit().putString(KEY, Base64.encodeToString(fresh, Base64.NO_WRAP)).commit()) { "could not persist DB key" }
        return fresh
    }

    private companion object { const val KEY = "passphrase" }
}
