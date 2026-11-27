package com.rokid.context.phone.ui.settings.privacy.zones

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.rokid.context.core.ui.theme.PcColors
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
    var picking by remember { mutableStateOf(false) }
    val selected = p.ui.selectedId == a.id
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The colour chip: the colour this area has on the map; tap to choose another.
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(SeedPalette.argb(a.colour))).border(if (selected) 3.dp else 1.dp, PcColors.Ink, RoundedCornerShape(6.dp))
                .clickable { picking = true }.semantics { contentDescription = "Map colour: ${SeedPalette.name(a.colour)}. Tap to change." }.testTag("seed_swatch_${a.id}"))
            Text(p.ui.rows.firstOrNull { it.id == a.id }?.text ?: a.label,
                Modifier.weight(1f).clickable { onFocus() }.testTag("seed_area_${a.id}"), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.Bold else null)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryAction("Rename", { renaming = true }, tag = "seed_rename_${a.id}")
            SecondaryAction("Seed again", { p.seedBox(SeedEstimate.boxOf(a.box)) }, tag = "seed_again_${a.id}", enabled = !p.ui.seeding)
            if (a.failedCells > 0) SecondaryAction("Retry failed cells", { p.retryFailed(a.id) }, tag = "seed_retry_${a.id}", enabled = !p.ui.seeding)
            SecondaryAction("Remove", { p.askRemove(a.id) }, tag = "seed_remove_${a.id}")
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false }, modifier = Modifier.testTag("seed_palette_dialog"),
            title = { Text("Map colour") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeedPalette.colours.chunked(5).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { c, col ->
                                val i = r * 5 + c
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(col.argb)).border(if (i == a.colour) 3.dp else 1.dp, PcColors.Ink, RoundedCornerShape(8.dp))
                                    .clickable { p.setColour(a.id, i); picking = false }.semantics { contentDescription = col.name }.testTag("seed_palette_$i"))
                            }
                        }
                    }
                    Text("Now: ${SeedPalette.name(a.colour)}", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
                }
            },
            confirmButton = { TextButton({ picking = false }, Modifier.testTag("seed_palette_close")) { Text("Close") } },
        )
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
