package com.rokid.context.phone.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcColors

/** E2 fills these. */
class StepCallbacks

data class StepCopy(val title: String, val body: String)

fun stepCopy(step: Step): StepCopy = when (step) {
    Step.WELCOME -> StepCopy("Proactive Context", "Your glasses notice. Your phone remembers. Ask anything about your day.")
    Step.PERMISSIONS -> StepCopy("A few permissions", "Nothing here is required. You can change any of it later.")
    Step.GLASSES -> StepCopy("Connect your glasses", "Authorize your Rokid glasses so they can send moments to your phone.")
    Step.AI -> StepCopy("Choose your AI", "Pick the model that answers your questions.")
    Step.PERSONA -> StepCopy("Persona & language", "How answers should sound.")
    Step.GESTURES -> StepCopy("Glasses gestures", "Everything you need is one tap away.")
    Step.DONE -> StepCopy("You're set", "Tap your glasses or the mic on Home to ask.")
}

fun OnboardingUiState.primaryLabel(): String = when (step) {
    Step.WELCOME -> "Get started"
    Step.GLASSES -> if (glasses == LinkState.CONNECTED) "Continue" else "Authorize glasses"
    Step.DONE -> "Open Home"
    else -> "Continue"
}

fun OnboardingUiState.primaryEnabled(): Boolean = true

fun OnboardingUiState.secondaryLabel(): String? = when (step) {
    Step.GLASSES -> if (glasses == LinkState.CONNECTED) null else "Set up later"
    else -> null
}

@Composable
internal fun StepBody(state: OnboardingUiState, cb: WizardCallbacks) {
    when (state.step) {
        Step.PERMISSIONS -> PermissionsBody(state, cb)
        Step.GLASSES -> GlassesBody(state)
        else -> Unit
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PcColors.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun PermRow(title: String, why: String, state: PermState, tag: String, onAllow: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
            Text(why, style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
        }
        when (state) {
            PermState.GRANTED -> Text("Allowed \u2713", color = PcColors.MintDeep)
            PermState.DENIED_FOREVER -> TextButton(onSettings) { Text("Open settings") }
            PermState.NOT_ASKED, PermState.DENIED -> TextButton(onAllow) { Text("Allow") }
        }
    }
}

@Composable
private fun PermissionsBody(state: OnboardingUiState, cb: WizardCallbacks) {
    Card {
        PermRow("Microphone", "For asking from the phone", state.mic, "perm_mic", cb.requestMic, cb.openAppSettings)
        state.voice?.let { v ->
            Row(Modifier.fillMaxWidth().testTag("voice_line"), verticalAlignment = Alignment.CenterVertically) {
                Text(v.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink)
                when (v.action) {
                    VoiceAction.DOWNLOAD -> TextButton(cb.downloadPack) { Text("Download") }
                    VoiceAction.OPEN_SETTINGS -> TextButton(cb.openSpeechSettings) { Text("Open settings") }
                    VoiceAction.NONE -> Unit
                }
            }
        }
        PermRow("Notifications", "For reminders and suggestions", state.notifications, "perm_notifications", cb.requestNotifications, cb.openAppSettings)
        Text("Calendar: You'll be asked when you turn on Calendar.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
    }
}

@Composable
private fun GlassesBody(state: OnboardingUiState) {
    val line = when (state.glasses) {
        LinkState.CONNECTED -> "Connected \u2713"
        LinkState.CONNECTING -> "Connecting\u2026"
        LinkState.NOT_AUTHORIZED -> "Not connected yet"
        else -> "Glasses not reachable right now"
    }
    Card { Text(line, Modifier.testTag("glasses_state"), style = MaterialTheme.typography.titleMedium, color = PcColors.Ink) }
}
