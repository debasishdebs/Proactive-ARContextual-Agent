package com.rokid.context.phone.canvas.helper

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.DeletionGraph
import com.rokid.context.orchestration.MemoryWipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** WP2 RED group: the `py_exec` grant defaults off, is a registered deletion-graph pref, and the real store is the prefs file. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HelperGrantsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clean() { ctx.getSharedPreferences(HelperGrants.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun grantDefaultsOffAndPersistsInThePrefsFile() {
        assertFalse(HelperGrants.of(ctx).isOn(HelperGrants.PY_EXEC))
        assertTrue(HelperGrants.of(ctx).set(HelperGrants.PY_EXEC, true))
        assertTrue("a fresh instance reads the same file", HelperGrants.of(ctx).isOn(HelperGrants.PY_EXEC))
        assertTrue(ctx.getSharedPreferences("canvas_helper_grants", Context.MODE_PRIVATE).getBoolean("py_exec", false))
        assertTrue(HelperGrants.of(ctx).set(HelperGrants.PY_EXEC, false))
        assertFalse(HelperGrants.of(ctx).isOn(HelperGrants.PY_EXEC))
    }

    @Test fun onlyPyExecIsAGrant() {
        val g = HelperGrants.of(ctx)
        assertEquals(setOf("py_exec"), HelperGrants.KNOWN)
        assertFalse(g.set("tool_build", true))
        assertFalse(g.isOn("tool_build"))
        assertFalse(ctx.getSharedPreferences(HelperGrants.PREFS, Context.MODE_PRIVATE).contains("tool_build"))
    }

    @Test fun aFailedWriteLeavesTheGrantOff() {
        val store = MemoryGrantStore().also { it.failWrites = true }
        val g = HelperGrants(store)
        assertFalse(g.set(HelperGrants.PY_EXEC, true))
        assertFalse(g.isOn(HelperGrants.PY_EXEC))
    }

    @Test fun allOffTurnsEveryGrantOff() {
        val g = HelperGrants.of(ctx)
        g.set(HelperGrants.PY_EXEC, true)
        assertTrue(g.allOff())
        assertFalse(g.isOn(HelperGrants.PY_EXEC))
    }

    @Test fun registeredInTheDeletionGraphAndClearedByDeleteAll() {
        val e = DeletionGraph.PREFS.single { it.name == "canvas_helper_grants" }
        assertEquals(DeletionGraph.Fate.WIPED, e.fate)
        assertEquals("the phone constant and the registry name are one", HelperGrants.PREFS, e.name)
        assertTrue(HelperGrants.PREFS in MemoryWipe.WIPED_PREFS)
        assertEquals(MemoryWipe.HELPER_GRANTS_PREFS, HelperGrants.PREFS)
        HelperGrants.of(ctx).set(HelperGrants.PY_EXEC, true)
        val prefs = ctx.getSharedPreferences(HelperGrants.PREFS, Context.MODE_PRIVATE)
        assertTrue(MemoryWipe.clearWipedPrefs(prefs, HelperGrants.PREFS))
        assertFalse("Delete all leaves the grant off", HelperGrants.of(ctx).isOn(HelperGrants.PY_EXEC))
    }
}
