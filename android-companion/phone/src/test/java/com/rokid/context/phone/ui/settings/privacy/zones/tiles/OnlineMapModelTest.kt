package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The street-map switch: OFF by default, a first-time explanation whose Cancel leaves it OFF, the cache purged by switching off and by Delete all. */
class OnlineMapModelTest {
    private var purges = 0
    private fun model(store: OnlineMapStore = OnlineMapStore(), available: Boolean = true) = OnlineMapModel(store, available) { purges++ }

    @Test fun defaultsOff() {
        val m = model()
        assertTrue(m.ui.value.available); assertFalse(m.ui.value.enabled); assertFalse(m.ui.value.askFirst)
    }

    @Test fun theFirstTurnOnAsksAndStaysOffUntilConfirmed() {
        val store = OnlineMapStore(); val m = model(store)
        m.requestEnabled(true)
        assertTrue(m.ui.value.askFirst); assertFalse(m.ui.value.enabled); assertFalse(store.enabled); assertFalse(store.consented)
    }

    @Test fun cancelLeavesItOffAndItAsksAgainNextTime() {
        val store = OnlineMapStore(); val m = model(store)
        m.requestEnabled(true); m.cancelFirst()
        assertFalse(m.ui.value.askFirst); assertFalse(m.ui.value.enabled); assertFalse(store.enabled); assertFalse(store.consented)
        m.requestEnabled(true)
        assertTrue("still the first time", m.ui.value.askFirst)
    }

    @Test fun confirmTurnsItOnAndLaterTurnOnsDoNotAsk() {
        val store = OnlineMapStore(); val m = model(store)
        m.requestEnabled(true); m.confirmFirst()
        assertTrue(m.ui.value.enabled); assertFalse(m.ui.value.askFirst); assertTrue(store.enabled); assertTrue(store.consented)
        m.requestEnabled(false)
        assertFalse(m.ui.value.enabled); assertFalse(store.enabled); assertTrue("the consent is kept", store.consented)
        m.requestEnabled(true)
        assertTrue(m.ui.value.enabled); assertFalse(m.ui.value.askFirst)
    }

    @Test fun confirmWithoutTheDialogDoesNothing() {
        val m = model(); m.confirmFirst()
        assertFalse(m.ui.value.enabled)
    }

    @Test fun turningItOffPurgesTheTileCache() {
        val m = model(); m.requestEnabled(true); m.confirmFirst()
        assertEquals(0, purges)
        m.requestEnabled(false)
        assertEquals(1, purges)
    }

    @Test fun deleteAllSwitchesOffForgetsTheConsentAndPurges() {
        val store = OnlineMapStore(); val m = model(store)
        m.requestEnabled(true); m.confirmFirst()
        m.wipe()
        assertFalse(m.ui.value.enabled); assertFalse(store.enabled); assertFalse(store.consented); assertEquals(1, purges)
        m.requestEnabled(true)
        assertTrue("explained again after Delete all", m.ui.value.askFirst)
    }

    @Test fun aSavedOnStateIsRestoredOnlyWhereTheBuildHasAProvider() {
        val store = OnlineMapStore().also { it.enabled = true; it.consented = true }
        assertTrue(model(store).ui.value.enabled)
        val none = model(store, available = false)
        assertFalse(none.ui.value.enabled); assertFalse(none.ui.value.available)
        none.requestEnabled(true)
        assertFalse("a keyless release build cannot turn it on", none.ui.value.enabled); assertFalse(none.ui.value.askFirst)
    }

    @Test fun theDialogSaysWhatIsSentAndWhatIsNot() {
        val w = OnlineMapWords.DIALOG_BODY
        for (s in listOf("IP address", "roughly where you are looking", "Nothing else is sent", "not sent as such", "Delete all")) assertTrue(s, w.contains(s))
        assertTrue(OnlineMapWords.SWITCH.startsWith("Show a street map (downloads map tiles from MapTiler)"))
    }

    @Test fun theWipeListenerResetsTheInMemorySwitchAndConsent() {
        val store = OnlineMapStore(); val m = model(store)
        m.requestEnabled(true); m.confirmFirst()
        kotlinx.coroutines.runBlocking { OnlineMapWipeListener { m }.onDeleteAll(); OnlineMapWipeListener { null }.onDeleteAll() }
        assertFalse(m.ui.value.enabled); assertFalse(store.enabled); assertFalse(store.consented); assertEquals(1, purges)
    }

    @Test fun thePrefsAndCacheNamesMatchWhatDeleteAllClears() {
        assertEquals(com.rokid.context.orchestration.MemoryWipe.ONLINE_MAP_PREFS, OnlineMapStore.PREFS)
        assertEquals(com.rokid.context.orchestration.MemoryWipe.ONLINE_TILES_DIR, com.rokid.context.phone.ui.settings.privacy.zones.online.OnlineMapGraph.CACHE_DIR)
        assertTrue(OnlineMapStore.PREFS in com.rokid.context.orchestration.MemoryWipe.WIPED_PREFS)
    }
}
