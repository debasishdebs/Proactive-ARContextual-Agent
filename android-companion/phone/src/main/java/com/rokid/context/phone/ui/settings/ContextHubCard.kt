package com.rokid.context.phone.ui.settings

import androidx.compose.foundation.layout.Column
import com.rokid.context.core.ui.components.SoftCard
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.model.context.ContextState

/** One reading of the context hub for Diagnostics: the committed state's enum name and the counters (spec §10). Numbers and enum names only. */
data class ContextHubCardData(val state: ContextState, val counters: Map<String, Long>) {
    private companion object {
        val COUNTS = listOf(
            "sends" to "sends", "changes" to "changes", "ar" to "ar", "steps" to "steps", "locFixes" to "loc", "locOnS" to "locOnS", "bt" to "bt",
            "aa" to "aa", "air" to "air", "hints" to "hints", "watch" to "watch", "watchRej" to "watchRej",
        )
    }
    private fun n(key: String) = counters[key] ?: 0L
    private fun batt(key: String) = n(key).let { if (it < 0) "na" else "$it%" }

    /** (test tag, text) rows. Never a device name, address, coordinate, caption or certificate. */
    fun rows(): List<Pair<String, String>> = listOf(
        "diag_ctx_state" to "State ${state.name}",
        "diag_ctx_counts" to COUNTS.joinToString(" · ") { (label, key) -> "$label ${n(key)}" },
        "diag_ctx_intent" to "intent ${n("intent")} · intentFail ${n("intentFail")} · intentSkip ${n("intentSkip")}",
        "diag_ctx_batt" to "Phone battery ${batt("battStart")} -> ${batt("battNow")}",
        "diag_ctx_time" to "Seconds per state: " + ContextState.entries.joinToString(" · ") { "${it.name} ${n("state_${it.name}_s")}" },
    )
}

@Composable
fun ContextHubCard(data: ContextHubCardData) {
    SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("diag_ctx")) {
        Column(Modifier.padding(16.dp)) { for ((tag, text) in data.rows()) NoticeText(text, tag = tag) }
    }
}
