package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.rokid.context.core.model.WEAR_PARK_MAX_SEC
import com.rokid.context.core.model.WEAR_PARK_MIN_SEC
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SwitchRow

/** Wear detection helpers: the pure bits behind the Settings card. */
object WearGuide {
    /** The wear signal must be missing longer than this before the card warns. */
    const val MISSING_AFTER_MS = 3 * 60_000L
    /** The global Hi Rokid app (the package the glasses link already targets). */
    const val HI_ROKID_PACKAGE = "com.rokid.sprite.global.aiapp"
    val LAG_SEC = listOf(30, 60, 120, 300, 600, 900, 1_800)

    fun missingLong(unknownSinceMs: Long?, nowMs: Long): Boolean = unknownSinceMs != null && nowMs - unknownSinceMs > MISSING_AFTER_MS
    fun lagOptions(current: Int): List<Int> = (LAG_SEC + current.coerceIn(WEAR_PARK_MIN_SEC, WEAR_PARK_MAX_SEC)).distinct().sorted()

    /** A launch intent for Hi Rokid, or null when it is not installed. */
    fun launchIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage(HI_ROKID_PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val STEPS = listOf(
        "Open Hi Rokid and select your glasses.",
        "Open the glasses settings and find the wear detection (wearing) option.",
        "Turn wear detection on, then come back here.",
    )
}

@Composable
fun WearDetectionCard(
    state: CaptureSettingsUiState,
    onEnabled: (Boolean) -> Unit, onUnknownSec: (Int) -> Unit, onWornSec: (Int) -> Unit, onOpenHiRokid: (() -> Unit)?,
) {
    SettingsCard(Modifier.testTag("wear_card")) {
        Text("Wear detection", style = MaterialTheme.typography.titleMedium)
        NoticeText(if (state.wearSignalKnown) "Hi Rokid wear signal: available" else "Hi Rokid wear signal: not available yet", tag = "wear_signal")
        if (state.wearMissingLong) NoticeText("No wear signal from Hi Rokid for more than 3 minutes. Photos still pause when you are still for a while, but they cannot tell when you take the glasses off.", error = true, tag = "wear_missing")
        NoticeText("Turning it on in Hi Rokid may use more battery", tag = "wear_battery")
        NoticeText(WearGuide.STEPS.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"), tag = "wear_steps")
        if (onOpenHiRokid != null) TextButton(onClick = onOpenHiRokid, modifier = Modifier.testTag("open_hi_rokid")) { Text("Open Hi Rokid") }
        SwitchRow("Pause when the glasses stay still", "Fallback when Hi Rokid does not say the glasses are off.", state.wearParkEnabled, onEnabled, tag = "wear_park_enabled")
        if (state.wearParkEnabled) {
            val unknown = WearGuide.lagOptions(state.wearParkUnknownSec)
            ChoiceRow("Still for (no wear signal)", unknown.map(CaptureIntervals::label), unknown.indexOf(state.wearParkUnknownSec.coerceIn(WEAR_PARK_MIN_SEC, WEAR_PARK_MAX_SEC)), { onUnknownSec(unknown[it]) }, tag = "wear_park_unknown")
            val worn = WearGuide.lagOptions(state.wearParkWornSec)
            ChoiceRow("Still for (Hi Rokid says worn)", worn.map(CaptureIntervals::label), worn.indexOf(state.wearParkWornSec.coerceIn(WEAR_PARK_MIN_SEC, WEAR_PARK_MAX_SEC)), { onWornSec(worn[it]) }, tag = "wear_park_worn")
        }
    }
}
