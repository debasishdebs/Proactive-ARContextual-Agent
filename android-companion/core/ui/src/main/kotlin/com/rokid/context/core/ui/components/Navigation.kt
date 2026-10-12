package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

data class PillNavItem(val route: String, val label: String, val icon: ImageVector)

/** Floating white pill with a soft shadow; the selected tab sits on a mint chip (Live Fit `PillNav`). */
@Composable
fun PillNav(items: List<PillNavItem>, selectedRoute: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(32.dp)
    Row(
        modifier.shadow(16.dp, pill).clip(pill).background(PcColors.Surface).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item.route == selectedRoute
            val tint = if (isSelected) PcColors.MintDeep else PcColors.ChipSlate.second
            Column(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSelected) PcColors.ChipMint.first else Color.Transparent)
                    .clickable { onSelect(item.route) }
                    .semantics(mergeDescendants = true) { selected = isSelected; role = Role.Tab }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = tint)
            }
        }
    }
}
