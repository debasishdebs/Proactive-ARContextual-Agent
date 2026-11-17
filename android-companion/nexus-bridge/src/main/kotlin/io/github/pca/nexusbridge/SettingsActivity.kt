package io.github.pca.nexusbridge

import android.app.Activity
import android.os.Bundle
import com.anezium.rokidbus.client.ui.NexusUi

/** The bridge's settings (opened from Nexus Plugin access): state, the OFF-by-default note, and how to remove the bridge. No launcher entry; no switches (they live in PCA). */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = NexusUi.column(this)
        col.addView(NexusUi.rowTitle(this, getString(R.string.settings_title)))
        col.addView(NexusUi.rowSub(this, getString(R.string.settings_state)))
        col.addView(NexusUi.rowSub(this, getString(R.string.settings_defaults)))
        col.addView(NexusUi.sectionLabel(this, getString(R.string.settings_remove_label)))
        col.addView(NexusUi.rowSub(this, getString(R.string.settings_remove_hint)))
        setContentView(NexusUi.screen(this, col))
    }
}
