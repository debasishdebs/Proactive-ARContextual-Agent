package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPlace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex v3 r1 P1-3 (pin hook) and P1-6 (an unopenable encrypted store disables recording instead of assuming an empty history). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveGraphTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val pinId = "user:00000000-0000-0000-0000-000000000001"
    private val real = ProactiveGraph.openEncryptedPrefs
    private fun prefs() = ctx.getSharedPreferences("test_graph_shadow", Context.MODE_PRIVATE)
    private fun rec(id: String, vararg src: String) = SuggestionRecord(id, 1_800_000_000_000L, "f2:$id", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", src.toList(), listOf("arrived"))

    @Before fun setUp() {
        prefs().edit().clear().commit(); ProactiveGraph.resetForTests(); ProactiveSettings.resetForTests(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ctx.getSharedPreferences(ProactiveSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit(); ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() {
        ProactiveGraph.openEncryptedPrefs = real; ProactiveGraph.resetForTests(); ProactiveSettings.resetForTests()
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null); prefs().edit().clear().commit()
    }

    private fun place(note: String, name: String = "Lion's Paw") = UserPlace(pinId, name, PlaceKinds.FORT, 7.0, 80.0, note = note)

    @Test fun aPinDeletedOrEditedWithdrawsTheRecordsBuiltFromItAtOnce() {
        ProactiveGraph.openEncryptedPrefs = { prefs() }
        ProactiveGraph.registerPinHook(ctx)
        val pins = UserPinStore(ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE))
        assertTrue(pins.write(listOf(place("a note"))))
        val store = ProactiveGraph.store(ctx)
        store.add(rec("pinned", "mem:m", "pin:$pinId")); store.add(rec("other", "mem:m2"))
        assertTrue(pins.write(listOf(place("a note")))) // unchanged: nothing is withdrawn
        assertEquals(2, store.records().size)
        assertTrue(pins.write(listOf(place("another note")))) // edited
        assertEquals(listOf("other"), store.records().map { it.id })
        store.add(rec("pinned2", "mem:m", "pin:$pinId"))
        assertTrue(pins.write(emptyList())) // deleted
        assertEquals(listOf("other"), store.records().map { it.id })
    }

    @Test fun anUnopenableEncryptedStoreDisablesRecordingAndKeepsNothingInMemory() {
        ProactiveGraph.openEncryptedPrefs = { throw java.security.GeneralSecurityException("keystore") }
        val settings = ProactiveSettings.of(ctx)
        settings.setMaster(true); assertTrue(settings.active())
        val s = ProactiveGraph.store(ctx)
        assertSame(UnavailableSuggestionStore, s); assertFalse(ProactiveGraph.storageAvailable(ctx))
        assertFalse("master forced off", settings.master.value); assertFalse(settings.active()); assertTrue(settings.storageUnavailable.value)
        assertFalse("also on disk", ctx.getSharedPreferences(ProactiveSettings.PREFS, Context.MODE_PRIVATE).getBoolean("master", true))
        settings.setMaster(true); assertFalse("it cannot be switched on again", settings.master.value)
        assertFalse(s.add(rec("x", "mem:m"))); assertTrue(s.records().isEmpty()); assertFalse(s.addNever("f2:k"))
        assertFalse("the seam the engine reads says off", ProactiveGraph.seams(ctx).enabled())
        assertSame("the failure is not retried into a fresh empty store", s, ProactiveGraph.store(ctx))
    }

    @Test fun aRunWithAnUnavailableStoreRecordsNothing() = kotlinx.coroutines.runBlocking {
        ProactiveGraph.openEncryptedPrefs = { throw IllegalStateException("keystore") }
        ProactiveSettings.of(ctx).setMaster(true)
        val run = ProactiveGraph.engine(ctx).run(TriggerKind.TICK)
        assertEquals(Gate.SWITCH, run.gate); assertTrue(UnavailableSuggestionStore.records().isEmpty())
    }

    @Test fun theReplayerRefusesToRunWithoutTheNeverList() {
        ProactiveGraph.openEncryptedPrefs = { throw IllegalStateException("keystore") }
        val r = ProactiveGraph.replayer(ctx)
        val failed = try { kotlinx.coroutines.runBlocking { r.replay(java.time.LocalDate.of(2026, 11, 18), 60, emptyList()) }; false } catch (e: IllegalStateException) { true }
        assertTrue(failed)
    }

    @Test fun theVehicleStateIsUnknownByDefaultAndUnknownIsDriving() {
        assertTrue(ProactiveEnvironment.driving()); assertFalse(ProactiveEnvironment.drivingSignalAvailable)
        assertTrue(ProactiveGraph.seams(ctx).driving())
    }
}
