package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ChipRow
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.activity.ActivityModel.plural
import java.time.LocalDate

@Composable
fun DayScreen(state: DayUiState, onBack: () -> Unit, onOpenMemory: (String) -> Unit, onOpenFrames: (LocalDate) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(PcColors.Surface)) {
        item { ScreenHeader(if (state.found) state.title else "Day", onBack) }
        when {
            state.loading -> Unit
            !state.found -> item { Text("Day not found", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
            else -> {
                val date = state.date
                if (state.frameCount > 0 && date != null) {
                    item { ChipRow(Icons.Outlined.PhotoLibrary, PcColors.ChipMint, "View ${plural(state.frameCount, "photo")}", onClick = { onOpenFrames(date) }) }
                }
                if (state.sections.isEmpty()) {
                    item { Text("No memories saved this day", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp)) }
                } else {
                    timelineItems(state.sections, onOpenMemory)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
