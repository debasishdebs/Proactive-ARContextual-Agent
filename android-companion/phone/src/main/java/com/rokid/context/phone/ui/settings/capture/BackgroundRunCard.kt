package com.rokid.context.phone.ui.settings.capture

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.rokid.context.phone.hub.BackgroundPolicy
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard

/** Settings > Capture > "Stop when the glasses are away": the choices and their words (pure, tested). */
object AutoStopCopy {
    const val TITLE = "Stop when the glasses are away"
    const val ROW = "Stop after"
    val OPTIONS: List<Int> = BackgroundPolicy.AUTO_STOP_PRESETS
    fun label(min: Int): String = if (min == BackgroundPolicy.AUTO_STOP_NEVER) "Never" else if (min % 60 == 0) "${min / 60} h" else "$min min"
    /** The option list with the stored value (always a valid one) included. */
    fun options(current: Int): List<Int> = (OPTIONS + BackgroundPolicy.cleanAutoStopMin(current)).distinct()
    const val NOTE = "When the glasses are off your head or not connected for this long, Proactive Context stops running in the background by itself, and starts again when you open the app or tap Reconnect. Never keeps it running as before."
}

/** The "Run in the background" card: what it says and offers, from whether the app is already exempt from battery optimization. */
object BackgroundRunModel {
    data class Model(val title: String, val body: String, val exempt: Boolean, val buttonLabel: String?)

    const val TITLE = "Run in the background"
    const val BODY_OFF = "Android does not let an app start itself in the background. Allowing it to ignore battery optimization lets Proactive Context start again by itself after it stopped because the glasses were away (at most once an hour). Without it, you start it by opening the app or tapping Reconnect. It never asks on its own."
    const val BODY_ON = "Allowed. Proactive Context may start again by itself after it stopped because the glasses were away (at most once an hour, never after you stopped it by hand)."
    const val BODY_UNSUPPORTED = "Not needed on this version of Android."
    const val BUTTON = "Allow in the background"

    fun of(exempt: Boolean, sdk: Int): Model = when {
        sdk < Build.VERSION_CODES.M -> Model(TITLE, BODY_UNSUPPORTED, exempt = true, buttonLabel = null)
        exempt -> Model(TITLE, BODY_ON, exempt = true, buttonLabel = null)
        else -> Model(TITLE, BODY_OFF, exempt = false, buttonLabel = BUTTON)
    }

    /** The system dialog for this app; only ever started by the user's tap. */
    @SuppressLint("BatteryLife") // a user-tapped request, explained on this card; the app does not run without the glasses link otherwise
    fun requestIntent(packageName: String): Intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
}

@Composable
fun AutoStopCard(autoStopMin: Int, onAutoStop: (Int) -> Unit) {
    SettingsCard {
        Text(AutoStopCopy.TITLE, style = MaterialTheme.typography.titleMedium)
        val opts = AutoStopCopy.options(autoStopMin)
        com.rokid.context.phone.ui.settings.ChoiceRow(AutoStopCopy.ROW, opts.map(AutoStopCopy::label), opts.indexOf(BackgroundPolicy.cleanAutoStopMin(autoStopMin)), { onAutoStop(opts[it]) }, tag = "auto_stop")
        NoticeText(AutoStopCopy.NOTE, tag = "auto_stop_note")
    }
}

/** [exempt] and [onRequest] come from the destination (it owns the Context): the card itself reaches for nothing. [onRequest] runs only from the button's tap. */
@Composable
fun BackgroundRunCard(exempt: Boolean, onRequest: () -> Unit) {
    val m = BackgroundRunModel.of(exempt, Build.VERSION.SDK_INT)
    SettingsCard(Modifier.testTag("background_run")) {
        Text(m.title, style = MaterialTheme.typography.titleMedium)
        NoticeText(m.body, tag = "background_run_note")
        m.buttonLabel?.let { label -> TextButton(onClick = onRequest, modifier = Modifier.testTag("background_run_button")) { Text(label) } }
    }
}
