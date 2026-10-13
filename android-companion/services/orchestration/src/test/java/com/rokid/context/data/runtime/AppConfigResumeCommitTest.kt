package com.rokid.context.data.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fix round 2: the resume flag flip is written synchronously, so a crash after extendTtl can't drop it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppConfigResumeCommitTest {
    /** Records, per key written, whether the edit was finished with commit() or apply(). */
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val finishes = mutableListOf<Pair<Set<String>, String>>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            if (name != "app_config") return real
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val e = real.edit()
                    val keys = mutableSetOf<String>()
                    return object : SharedPreferences.Editor by e {
                        override fun putBoolean(key: String, value: Boolean) = apply { keys += key; e.putBoolean(key, value) }
                        override fun putLong(key: String, value: Long) = apply { keys += key; e.putLong(key, value) }
                        override fun remove(key: String) = apply { keys += key; e.remove(key) }
                        override fun commit(): Boolean { finishes += keys.toSet() to "commit"; return e.commit() }
                        override fun apply() { finishes += keys.toSet() to "apply"; e.apply() }
                    }
                }
            }
        }
    }

    @Test fun resumeCommitsThePipelineFlagFlip() {
        val ctx = RecordingContext(ApplicationProvider.getApplicationContext())
        AppConfigStore.setPipelineEnabled(ctx, false, nowEpochMs = 1_000L)
        ctx.finishes.clear()
        AppConfigStore.setPipelineEnabled(ctx, true, nowEpochMs = 2_000L)
        val flagWrites = ctx.finishes.filter { "pipeline_enabled" in it.first }
        assertEquals(1, flagWrites.size)
        assertEquals("commit", flagWrites.single().second)
        assertTrue(AppConfigStore.isPipelineEnabled(ctx))
    }
}
