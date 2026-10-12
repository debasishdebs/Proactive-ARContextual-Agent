package com.rokid.context.phone.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors

/** Morning / Afternoon / Evening timeline (spec §2.4 Activity Today, Day drill-down). */
fun LazyListScope.timelineItems(sections: List<TimelineSection>, onOpenMemory: (String) -> Unit) {
    sections.forEach { section ->
        item(key = "label_${section.label}") { SectionLabel(section.label) }
        items(section.rows, key = { it.memoryId }) { row -> TimelineCard(row, onOpenMemory) }
    }
}

@Composable
private fun TimelineCard(row: TimelineRow, onOpenMemory: (String) -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(), onClick = { onOpenMemory(row.memoryId) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameThumb(row.framePath, size = 52.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.time, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
                Text(row.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ConfidenceChip(row.confidence)
        }
    }
}
