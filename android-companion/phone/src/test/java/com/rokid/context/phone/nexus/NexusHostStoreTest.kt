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
        s.settle(); assertFalse(NexusHostStore(prefs).wipeOwed()); assertEquals(setOf("wipe_gen"), prefs.all.keys)
        prefs.edit().putString("wipe_owed", "x").commit()
        assertTrue("an unreadable flag counts as owed", NexusHostStore(prefs).wipeOwed())
    }

    // ---- Codex v2 r2 P1-3: generations ----

    @Test fun aLateAcknowledgementOfAnOlderGenerationNeverClearsANewerDebt() {
        val s = NexusHostStore(prefs)
        val a = s.owe()                      // wipe A requested
        val b = s.owe()                      // notice B, Forget B: a newer debt
        assertTrue(b > a)
        s.settle(a)                          // the delayed acknowledgement of A
        assertTrue(s.wipeOwed()); assertTrue("also after a restart", NexusHostStore(prefs).wipeOwed())
        s.settle(b)
        assertFalse(NexusHostStore(prefs).wipeOwed())
        assertEquals("the generation never goes back", b, NexusHostStore(prefs).generation())
    }

    // ---- Codex v2 r2 P1-4: the host switch and the debt are one commit ----

    @Test fun leavingNexusWritesTheHostAndTheOwedWipeInOneCommit() {
        val s = NexusHostStore(prefs)
        assertTrue(s.set(HudHost.NEXUS)); assertFalse("choosing Nexus owes nothing", s.wipeOwed())
        var commits = 0
        val counting = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val e = prefs.edit()
                return object : android.content.SharedPreferences.Editor by e {
                    override fun putString(k: String?, v: String?) = apply { e.putString(k, v) }
                    override fun putBoolean(k: String?, v: Boolean) = apply { e.putBoolean(k, v) }
                    override fun putLong(k: String?, v: Long) = apply { e.putLong(k, v) }
                    override fun remove(k: String?) = apply { e.remove(k) }
                    override fun commit(): Boolean { commits++; return e.commit() }
                }
            }
        }
        val s2 = NexusHostStore(counting)
        assertTrue(s2.set(HudHost.PCA))
        assertEquals("one commit carries host and debt", 1, commits)
        val after = NexusHostStore(prefs)                       // a process that died right after the commit
        assertEquals(HudHost.PCA, after.current()); assertTrue("the owed wipe is there", after.wipeOwed())
    }

    @Test fun aNoticeMarkedShownIsOwedAtStartWhenTheHostIsPcaAndAnAckOfALaterWipeClearsTheMarker() {
        val s = NexusHostStore(prefs)
        s.noteShown()
        val restarted = NexusHostStore(prefs)                    // the host flipped to PCA, the process died before any debt was recorded
        assertFalse(restarted.wipeOwed())
        restarted.reconcile(hostIsPca = false); assertFalse("Nexus is the host: nothing owed yet", restarted.wipeOwed())
        restarted.reconcile(hostIsPca = true)
        assertTrue(restarted.wipeOwed())
        restarted.settle(restarted.generation())
        assertFalse(restarted.wipeOwed()); assertFalse(prefs.contains("shown_gen"))
        restarted.reconcile(hostIsPca = true); assertFalse("paid and nothing shown since", restarted.wipeOwed())
    }

    @Test fun nothingShownMeansNothingOwedAtStart() {
        val s = NexusHostStore(prefs); s.reconcile(hostIsPca = true)
        assertFalse(s.wipeOwed()); assertTrue(prefs.all.isEmpty())
    }
}
