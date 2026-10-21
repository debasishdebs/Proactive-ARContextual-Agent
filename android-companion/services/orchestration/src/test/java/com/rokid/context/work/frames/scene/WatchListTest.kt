package com.rokid.context.work.frames.scene

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WatchListTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun defaultsAndReset() {
        val p = prefs("wl_defaults")
        val s = WatchListStore(p)
        assertEquals(WatchListStore.DEFAULTS, s.items.value)
        s.set(listOf("bike", "umbrella"))
        assertEquals(listOf("bike", "umbrella"), s.items.value)
        assertEquals(listOf("bike", "umbrella"), WatchListStore(p).items.value) // persisted
        s.reset()
        assertEquals(WatchListStore.DEFAULTS, s.items.value)
        assertEquals(WatchListStore.DEFAULTS, WatchListStore(p).items.value)
    }

    @Test fun trimsDedupesCaps() {
        val s = WatchListStore(prefs("wl_clean"))
        s.set(listOf("  keys ", "Keys", "", "   ", "x".repeat(60)) + (1..30).map { "item $it" })
        val got = s.items.value
        assertEquals(20, got.size)
        assertEquals("keys", got[0])
        assertEquals(40, got[1].length)
        assertEquals(got.size, got.map { it.lowercase() }.distinct().size)
        s.set(listOf(" ", ""))   // nothing usable: back to the defaults, never an empty list
        assertEquals(WatchListStore.DEFAULTS, s.items.value)
    }

    @Test fun vectorsCachedUntilListOrSpaceChanges() = runBlocking {
        val s = WatchListStore(prefs("wl_cache"))
        s.set(listOf("a", "b"))
        val wv = WatchVectors(s)
        var calls = 0
        val embed: suspend (String) -> FloatArray = { calls++; FloatArray(8) { i -> (i + 1).toFloat() } }
        wv.vectors("fpA", 4, embed); assertEquals(2, calls)
        wv.vectors("fpA", 4, embed); assertEquals(2, calls)
        wv.vectors("fpB", 4, embed); assertEquals(4, calls)     // space changed
        wv.vectors("fpB", 2, embed); assertEquals(6, calls)     // dims changed
        s.set(listOf("a", "c"))
        wv.vectors("fpB", 2, embed); assertEquals(8, calls)     // list changed
        wv.vectors("fpB", 2, embed); assertEquals(8, calls)
    }

    @Test fun shortenRenormalises() = runBlocking {
        val s = WatchListStore(prefs("wl_short"))
        s.set(listOf("a"))
        val wv = WatchVectors(s)
        val v = wv.vectors("fp", 2) { floatArrayOf(3f, 4f, 12f, 0f) }.single()
        assertEquals(2, v.size)
        assertEquals(0.6f, v[0], 1e-5f); assertEquals(0.8f, v[1], 1e-5f)
        assertEquals(1f, wv.maxCos(floatArrayOf(0.6f, 0.8f), listOf(v))!!, 1e-5f)
        assertEquals(0f, wv.maxCos(floatArrayOf(0.8f, -0.6f), listOf(v))!!, 1e-5f)
        assertNull(wv.maxCos(floatArrayOf(1f, 0f), emptyList()))
    }
}
