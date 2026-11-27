package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction

/**
 * The ONE seeded-areas list, used by Pause zones and Map zones so they always match: title, the "N seeded areas · M places stored" header, one [SeedAreaRowContent] per area and the licence line.
 * [onFocus] is what a tap on a row does (the map screens focus their map on the area). Everything is read from places.db and the work state, so it renders with no network. No coordinates in any text.
 */
@Composable
fun SeededAreasContent(p: SeedPanel, onFocus: (SeedOverlay) -> Unit) {
    Text("Seeded areas", style = MaterialTheme.typography.titleMedium)
    p.ui.header?.let { NoticeText(it, tag = "seed_header") }
    p.ui.areas.forEach { a -> SeedAreaRowContent(p, a) { onFocus(a) } }
    if (p.ui.areas.isNotEmpty()) NoticeText(SeedAreaList.ATTRIBUTION, tag = "seed_list_attribution")
}

/**
 * One seeded-areas row: the line (name, state, places, cells, size, date; tap to focus the map), Rename (label only), Seed again (the pack skips cells that are fresh),
 * "Retry failed cells" when some did not seed, and Remove (area and its places / only the area).
 */
@Composable
fun SeedAreaRowContent(p: SeedPanel, a: SeedOverlay, onFocus: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    Column {
        Text(p.ui.rows.firstOrNull { it.id == a.id }?.text ?: a.label,
            Modifier.fillMaxWidth().clickable { onFocus() }.testTag("seed_area_${a.id}"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryAction("Rename", { renaming = true }, tag = "seed_rename_${a.id}")
            SecondaryAction("Seed again", { p.seedBox(SeedEstimate.boxOf(a.box)) }, tag = "seed_again_${a.id}", enabled = !p.ui.seeding)
            if (a.failedCells > 0) SecondaryAction("Retry failed cells", { p.retryFailed(a.id) }, tag = "seed_retry_${a.id}", enabled = !p.ui.seeding)
            SecondaryAction("Remove", { p.askRemove(a.id) }, tag = "seed_remove_${a.id}")
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(a.label.takeUnless { it.startsWith("Seeded area ") } ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false }, modifier = Modifier.testTag("seed_rename_dialog"),
            title = { Text("Rename seeded area") },
            text = { androidx.compose.material3.OutlinedTextField(text, { text = it.take(com.rokid.context.memory.place.PlacePack.MAX_AREA_NAME) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.testTag("seed_rename_field")) },
            confirmButton = { TextButton({ p.rename(a.id, text); renaming = false }, Modifier.testTag("seed_rename_save")) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }, Modifier.testTag("seed_rename_cancel")) { Text("Cancel") } },
        )
    }
}
