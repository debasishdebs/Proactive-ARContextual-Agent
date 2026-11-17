package com.rokid.context.phone.nexus

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NexusHostStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("nexus_host_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun theDefaultIsPcaAndNothingIsStoredUntilTheUserChooses() {
        val s = NexusHostStore(prefs)
        assertEquals(HudHost.PCA, s.current())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun theChoiceIsStoredAndOnlyTheExactWordMeansNexus() {
        val s = NexusHostStore(prefs)
        assertTrue(s.set(HudHost.NEXUS)); assertFalse(s.set(HudHost.NEXUS))
        assertEquals(HudHost.NEXUS, NexusHostStore(prefs).current())
        assertEquals(HudHost.NEXUS, s.host.value)
        prefs.edit().putString("host", "nexus").commit(); assertEquals(HudHost.PCA, NexusHostStore(prefs).current())
        prefs.edit().putString("host", "garbage").commit(); assertEquals(HudHost.PCA, NexusHostStore(prefs).current())
    }

    @Test fun theFileNameIsTheOneTheBackupRulesAndDeleteAllInventoryExclude() {
        assertEquals("nexus_host", NexusHostStore.PREFS)
        val xml = java.io.File("src/main/res/xml")
        for (f in listOf("backup_rules.xml", "data_extraction_rules.xml")) assertTrue(f, "sharedpref\" path=\"nexus_host.xml\"" in java.io.File(xml, f).readText())
        assertEquals(2, Regex("nexus_host\\.xml").findAll(java.io.File(xml, "data_extraction_rules.xml").readText()).count()) // cloud backup and device transfer
        assertEquals(com.rokid.context.memory.forget.DeletionGraph.Fate.KEPT, com.rokid.context.memory.forget.DeletionGraph.PREFS.single { it.name == "nexus_host" }.fate)
    }

    @Test fun theThreeSwitchesAreOffByDefaultStoredInTheSameFileAndOnlyTrueMeansOn() {
        val s = NexusHostStore(prefs)
        assertEquals(NexusSwitches(false, false, false), s.switchesNow()); assertTrue(prefs.all.isEmpty())
        assertTrue(s.setSwitches(NexusSwitches(voice = true))); assertFalse(s.setSwitches(NexusSwitches(voice = true)))
        assertEquals(NexusSwitches(voice = true), NexusHostStore(prefs).switchesNow())
        assertEquals(NexusSwitches(voice = true), s.switches.value)
        prefs.edit().putString("voice", "true").remove("tts").commit()
        assertEquals(NexusSwitches(), NexusHostStore(prefs).switchesNow())
    }

    @Test fun theWipeDebtIsStoredSynchronouslyAndSettledOnlyByAnAcknowledgement() {
        val s = NexusHostStore(prefs)
        assertFalse(s.wipeOwed()); assertTrue(prefs.all.isEmpty())
        s.owe(); assertTrue(s.wipeOwed()); assertTrue(NexusHostStore(prefs).wipeOwed())
        s.settle(); assertFalse(NexusHostStore(prefs).wipeOwed()); assertTrue(prefs.all.isEmpty())
        prefs.edit().putString("wipe_owed", "x").commit()
        assertTrue("an unreadable flag counts as owed", NexusHostStore(prefs).wipeOwed())
    }
}
