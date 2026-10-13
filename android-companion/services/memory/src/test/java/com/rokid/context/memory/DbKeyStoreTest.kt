package com.rokid.context.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P8 D4: the passphrase prefs are created once per process; each caller gets its own copy (it may zero it). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DbKeyStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var created = 0
    // Robolectric has no AndroidKeyStore: plain prefs stand in for EncryptedSharedPreferences.
    private val keys = DbKeys { c: Context -> created++; c.getSharedPreferences("db_key_test", Context.MODE_PRIVATE) as SharedPreferences }

    @Test fun passphraseIsStableAcrossCalls() {
        val a = keys.passphrase(ctx)
        val b = keys.passphrase(ctx)
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
        assertNotSame(a, b)
        a.fill(0)
        assertArrayEquals(b, keys.passphrase(ctx)) // zeroing a caller's copy never touches the stored key
    }

    @Test fun prefsAreCreatedOnce() {
        repeat(3) { keys.passphrase(ctx) }
        assertEquals(1, created)
    }
}
