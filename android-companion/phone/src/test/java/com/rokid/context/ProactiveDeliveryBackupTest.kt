package com.rokid.context

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.orchestration.proactive.FeedbackAction
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.PrefsSuggestionStore
import com.rokid.context.orchestration.proactive.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v3.1 adds NO new prefs file: the three delivery switches live in `proactive_settings`, the delivered markers and the feedback counters in the encrypted `proactive_shadow`. Both files stay excluded from backup and device
 * transfer (BackupRulesTest pins the exclusion list; here the new keys are pinned to those files, so a key can never land in a file that IS backed up). Kept apart from BackupRulesTest, which another change owns.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProactiveDeliveryBackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun xml(path: String) = File(path).readText()

    @Test fun theDeliverySwitchesLiveInTheExcludedSettingsFileAndTheCountersInTheExcludedStoreFile() {
        assertEquals("proactive_settings", ProactiveSettings.PREFS); assertEquals("proactive_shadow", PrefsSuggestionStore.PREFS)
        for (f in listOf("src/main/res/xml/backup_rules.xml", "src/main/res/xml/data_extraction_rules.xml")) for (n in listOf("proactive_settings", "proactive_shadow"))
            assertTrue("$f excludes $n", xml(f).contains("sharedpref:$n.xml") || xml(f).contains("path=\"$n.xml\"") || xml(f).contains("$n.xml"))
        val prefs = ctx.getSharedPreferences("test_backup_flags", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        ProactiveSettings(prefs).apply { setMaster(true); Surface.entries.forEach { setDeliver(it, true) } }
        assertEquals(setOf("master", "deliver_phone", "deliver_hud", "deliver_nexus"), prefs.all.keys)
        val store = ctx.getSharedPreferences("test_backup_store", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        PrefsSuggestionStore(store).recordFeedback(FeedbackAction.UP, "f2:k", ProactiveFamily.PLACE_RECALL, 1L)
        assertEquals(setOf("fb", "counts"), store.all.keys)
        assertFalse("no text in the counters", store.all.values.joinToString().contains("last visit"))
    }

    @Test fun deleteAllResetsEverySwitchAndTheFreshDefaultIsOff() {
        val prefs = ctx.getSharedPreferences("test_backup_reset", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val s = ProactiveSettings(prefs); s.setMaster(true); Surface.entries.forEach { s.setDeliver(it, true) }
        s.reset(); assertTrue(prefs.all.isEmpty()); Surface.entries.forEach { assertFalse(s.deliversTo(it)) }
        assertFalse(ProactiveSettings(prefs).let { it.master.value || it.deliverPhone.value || it.deliverHud.value || it.deliverNexus.value })
    }
}
