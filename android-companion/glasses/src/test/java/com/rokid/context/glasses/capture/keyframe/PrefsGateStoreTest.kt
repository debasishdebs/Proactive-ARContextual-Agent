package com.rokid.context.glasses.capture.keyframe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PrefsGateStoreTest {
    @Test fun ringKeepsLastFiveAcrossInstancesIncludingNegativeHashes() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("capture_guard", Context.MODE_PRIVATE)
        val a = PrefsGateStore(prefs)
        for (i in 1..7) a.push(-i.toLong(), i * 1_000_000_007L)
        a.setLastKeptAt(42)
        val b = PrefsGateStore(prefs)
        assertEquals((3..7).map { -it.toLong() to it * 1_000_000_007L }, b.lastKeyframes())
        assertEquals(42L, b.lastKeptAt())
    }
}
