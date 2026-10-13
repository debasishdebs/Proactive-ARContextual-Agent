package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

data class VersionChip(val version: Int, val active: Boolean, val testedOk: Boolean)

/** spec §2.3 `VersionStrip`: prompt versions newest first; Mint = active, Sky = selected, ✓ = saved after a passing test. */
@Composable
fun VersionStrip(versions: List<VersionChip>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(versions, key = { it.version }) { v ->
            val colors = when { v.active -> PcColors.ChipMint; v.version == selected -> PcColors.ChipSky; else -> PcColors.ChipSlate }
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(colors.first).clickable { onSelect(v.version) }
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag("version_${v.version}"),
            ) {
                Text(buildString { append("v${v.version}"); if (v.active) append(" · active"); if (v.testedOk) append(" ✓") }, color = colors.second, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
