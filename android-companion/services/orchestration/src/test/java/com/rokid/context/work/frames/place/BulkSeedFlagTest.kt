package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.orchestration.forget.DeveloperFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 27 Nov: the Developer flag `bulkSeed` is ON by default, persists, and an unreadable pref reads as OFF (the per-cell path, as before). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BulkSeedFlagTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun clear() { ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun defaultsOnAndPersistsBothWays() {
        clear()
        assertTrue(DeveloperFlags.bulkSeed(ctx))
        DeveloperFlags.setBulkSeed(ctx, false); ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().commit()
        assertFalse(DeveloperFlags.bulkSeed(ctx))
        DeveloperFlags.setBulkSeed(ctx, true)
        assertTrue(DeveloperFlags.bulkSeed(ctx))
        clear()
    }

    @Test fun aReadFailureIsOff() {
        val broken = object : android.content.ContextWrapper(ctx) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = throw IllegalStateException("prefs")
        }
        assertFalse(DeveloperFlags.bulkSeed(broken))
    }
}
