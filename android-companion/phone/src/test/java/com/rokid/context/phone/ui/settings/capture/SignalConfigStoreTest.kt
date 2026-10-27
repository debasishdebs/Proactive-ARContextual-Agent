package com.rokid.context.phone.ui.settings.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.plan.SignalConfigs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 A: the stored signal configuration (disabled catalogue signals + derived definitions), validated on write, in the `signal_config` prefs. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SignalConfigStoreTest {
    @After fun reset() { SignalConfigs.provider = { com.rokid.context.ai.canvas.plan.SignalConfig() } }

    private val walky = Json.parseToJsonElement("""{"steps":[{"name":"s","inputs":[{"name":"k","const":1.2}],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"s"}""") as JsonObject
    private fun prefs(name: String) = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences(name, 0).also { it.edit().clear().commit() }

    @Test fun emptyByDefaultAndSaveAndReloadWork() {
        val p = prefs("sc1")
        val s = SignalConfigStore(p)
        assertTrue(s.config.value.disabled.isEmpty() && s.config.value.derived.isEmpty())
        assertEquals(emptyList<String>(), s.saveDerived("walky", walky))
        assertTrue(s.setEnabled("accVar", false))
        val again = SignalConfigStore(p).config.value
        assertEquals(listOf("walky"), again.derived.map { it.name })
        assertEquals(setOf("accVar"), again.disabled)
    }

    @Test fun anInvalidDefinitionIsRefusedAndNothingIsStored() {
        val p = prefs("sc2")
        val s = SignalConfigStore(p)
        val bad = Json.parseToJsonElement(walky.toString().replace("stepHz", "nope")) as JsonObject
        assertTrue(s.saveDerived("walky", bad).any { "EXPR_UNKNOWN_SIGNAL" in it })
        assertTrue(s.saveDerived("Bad Name", walky).isNotEmpty())
        assertTrue(SignalConfigStore(p).config.value.derived.isEmpty())
    }

    @Test fun anUnknownSignalCannotBeDisabledAndAnAliasMapsToItsSignal() {
        val s = SignalConfigStore(prefs("sc3"))
        assertFalse(s.setEnabled("nope", false))
        assertTrue(s.setEnabled("headSpeed", false))
        assertEquals(setOf("gyroMax"), s.config.value.disabled)
        assertTrue(s.setEnabled("gyroMax", true))
        assertTrue(s.config.value.disabled.isEmpty())
    }

    @Test fun deleteAndClearRemoveDefinitions() {
        val s = SignalConfigStore(prefs("sc4"))
        s.saveDerived("a", walky); s.saveDerived("b", walky)
        s.deleteDerived("a")
        assertEquals(listOf("b"), s.config.value.derived.map { it.name })
        s.clear()
        assertTrue(s.config.value.derived.isEmpty())
    }

    @Test fun aCorruptStoredValueLoadsAsEmptyAndAnInvalidStoredDefinitionIsDropped() {
        val p = prefs("sc5")
        p.edit().putString("derived", "not json").putString("disabled", "[1,2").commit()
        assertTrue(SignalConfigStore(p).config.value.derived.isEmpty())
        p.edit().putString("derived", """{"x":{"steps":[]}}""").putString("disabled", """["gyroMax","bogus"]""").commit()
        val c = SignalConfigStore(p).config.value
        assertTrue(c.derived.isEmpty()); assertEquals(setOf("gyroMax"), c.disabled)
    }

    @Test fun theProviderFollowsTheStore() {
        val s = SignalConfigStore(prefs("sc6"))
        SignalConfigs.provider = { s.config.value }
        s.setEnabled("gyroMax", false)
        assertEquals(setOf("gyroMax"), SignalConfigs.current().disabled)
    }

    @Test fun aDeletedOrEditedDefinitionStaysInTheHistoryAcrossRestarts() {
        val p = prefs("sc7")
        val s = SignalConfigStore(p)
        s.saveDerived("walky", walky)
        s.deleteDerived("walky")
        assertTrue(s.config.value.derived.isEmpty())
        assertEquals(listOf("walky"), SignalConfigStore(p).history.flatMap { c -> c.derived.map { it.name } }.distinct())
        for (i in 1..30) s.saveDerived("w", Json.parseToJsonElement(walky.toString().replace("1.2", "$i.5")) as JsonObject)
        assertTrue(SignalConfigStore(p).history.size <= SignalConfigStore.MAX_HISTORY)
    }
}
