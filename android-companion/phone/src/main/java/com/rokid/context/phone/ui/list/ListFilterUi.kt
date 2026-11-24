package com.rokid.context.phone.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokid.context.core.ui.theme.PcColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** What the screen needs for the date and time filter (built from a [ListViewModel]); null on a list that has no period. */
class FilterUi(val range: kotlinx.coroutines.flow.StateFlow<DateTimeRange?>, val apply: (LocalDateTime, LocalDateTime) -> Boolean, val clear: () -> Unit)

/** The row that names the active range, with an x that clears it. */
@Composable
internal fun FilterChipRow(range: DateTimeRange, onClear: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clipBackground().testTag("list_filter_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(RangeFilter.label(range), style = MaterialTheme.typography.labelLarge, color = PcColors.MintDeep, modifier = Modifier.padding(start = 12.dp))
        IconButton(onClick = onClear, modifier = Modifier.size(36.dp).testTag("list_filter_clear")) { Icon(Icons.Rounded.Close, contentDescription = "Clear date filter", modifier = Modifier.size(18.dp)) }
    }
}

private fun Modifier.clipBackground(): Modifier = this.background(PcColors.ChipSky.first, RoundedCornerShape(20.dp))

private fun utcDay(ms: Long?): LocalDate? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
private fun utcMillis(d: LocalDate?): Long? = d?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

/** The Filter dialog: a calendar for the first and last day, a start and an end time, then Apply, Clear or Cancel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeFilterDialog(initial: DateTimeRange?, onApply: (LocalDateTime, LocalDateTime) -> Boolean, onClear: () -> Unit, onDismiss: () -> Unit) {
    val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = utcMillis(initial?.start?.toLocalDate()), initialSelectedEndDateMillis = utcMillis(initial?.end?.toLocalDate()))
    var from by rememberSaveable { mutableStateOf(RangeFilter.formatTime(initial?.start?.toLocalTime() ?: LocalTime.MIN)) }
    var to by rememberSaveable { mutableStateOf(RangeFilter.formatTime(initial?.end?.toLocalTime() ?: LocalTime.of(23, 59))) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).fillMaxWidth().testTag("list_filter_dialog"), shape = RoundedCornerShape(24.dp), color = PcColors.Surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                Text("Filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                Text("Pick the first and last day, then the times.", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                DateRangePicker(picker, modifier = Modifier.height(460.dp).testTag("list_filter_calendar"), title = null, headline = null, showModeToggle = false)
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(from, { from = it; problem = null }, label = { Text("Start time") }, placeholder = { Text("09:00") }, singleLine = true, modifier = Modifier.weight(1f).testTag("list_filter_from"))
                    OutlinedTextField(to, { to = it; problem = null }, label = { Text("End time") }, placeholder = { Text("17:30") }, singleLine = true, modifier = Modifier.weight(1f).testTag("list_filter_to"))
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).testTag("list_filter_problem")) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("list_filter_cancel")) { Text("Cancel", color = PcColors.InkSoft) }
                    TextButton(onClick = { onClear(); onDismiss() }, modifier = Modifier.testTag("list_filter_reset")) { Text("Clear", color = PcColors.InkSoft) }
                    TextButton(onClick = {
                        val first = utcDay(picker.selectedStartDateMillis)
                        val last = utcDay(picker.selectedEndDateMillis) ?: first
                        val t0 = RangeFilter.parseTime(from); val t1 = RangeFilter.parseTime(to)
                        problem = when {
                            first == null || last == null -> "Pick a start day on the calendar."
                            t0 == null || t1 == null -> "Type times like 09:00."
                            !onApply(RangeFilter.at(first, t0), RangeFilter.at(last, t1)) -> "The start must come before the end."
                            else -> { onDismiss(); null }
                        }
                    }, modifier = Modifier.testTag("list_filter_apply")) { Text("Apply", fontWeight = FontWeight.SemiBold, color = PcColors.MintDeep) }
                }
            }
        }
    }
}
