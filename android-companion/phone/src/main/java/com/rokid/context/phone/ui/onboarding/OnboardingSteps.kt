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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.rokid.context.core.model.Gesture
import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.GestureDefaults
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.SegmentedSwitch
import com.rokid.context.prompt.ReplyLanguage
import com.rokid.context.core.ui.theme.PcColors

class StepCallbacks(
    val selectSeed: () -> Unit = {},
    val selectPreset: (AiPreset) -> Unit = {},
    val onUrl: (String) -> Unit = {},
    val onApiVersion: (String) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onKey: (String) -> Unit = {},
    val test: () -> Unit = {},
    val setLanguage: (ReplyLanguage) -> Unit = {},
    val setStyle: (PersonaStyle) -> Unit = {},
)

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

fun OnboardingUiState.primaryEnabled(): Boolean = step != Step.AI || ai.canContinue

fun OnboardingUiState.secondaryLabel(): String? = when (step) {
    Step.GLASSES -> if (glasses == LinkState.CONNECTED) null else "Set up later"
    Step.AI -> "Set up later"
    else -> null
}

@Composable
internal fun StepBody(state: OnboardingUiState, cb: WizardCallbacks) {
    when (state.step) {
        Step.PERMISSIONS -> PermissionsBody(state, cb)
        Step.GLASSES -> GlassesBody(state)
        Step.AI -> AiBody(state.ai, cb.steps)
        Step.PERSONA -> PersonaBody(state.persona, cb.steps)
        Step.GESTURES -> GesturesBody()
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

@Composable
private fun Field(label: String, value: String, tag: String, onChange: (String) -> Unit, error: String? = null, secret: Boolean = false, hint: String? = null, uri: Boolean = false) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth().testTag(tag), singleLine = true, label = { Text(label) },
        placeholder = hint?.let { { Text(it) } }, isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = when {
            secret -> KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
            uri -> KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)
            else -> KeyboardOptions.Default
        },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun ChoiceCard(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) PcColors.ChipMint.first else PcColors.SurfaceSoft)
            .clickable(onClick = onClick).padding(14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(if (selected) "$text \u2713" else text, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink) }
}

@Composable
private fun AiBody(ai: AiStepState, cb: StepCallbacks) {
    Card {
        ai.seed?.let { ChoiceCard("Use ${it.name}", ai.useSeed, "ai_seed", cb.selectSeed) }
        // BUG-R3-2: the build seed has an address and deployment but no key; masked like Settings, stored via the vault.
        if (ai.seedNeedsKey) Field("API key", ai.key, "ai_seed_key", cb.onKey, secret = true)
        AiPreset.entries.forEach { p -> ChoiceCard(p.label, ai.preset == p, "ai_preset_${p.name}") { cb.selectPreset(p) } }
        ai.preset?.let { p ->
            Field("Address", ai.url, "ai_url", cb.onUrl, error = ai.urlError, hint = p.urlHint, uri = true)
            if (p == AiPreset.AZURE) Field("API version", ai.apiVersion, "ai_api_version", cb.onApiVersion, hint = "2024-10-21")
            Field(if (p == AiPreset.AZURE) "Deployment name" else "Model", ai.model, "ai_model", cb.onModel)
            if (p.hasKey) Field("API key", ai.key, "ai_key", cb.onKey, secret = true)
        }
        if (ai.seed != null || ai.preset != null) {
            TextButton(cb.test, enabled = ai.canTest, modifier = Modifier.testTag("ai_test")) { Text(if (ai.testing) "Testing\u2026" else "Test") }
        }
        ai.result?.let { Text(it.text, Modifier.testTag("ai_result").semantics { liveRegion = LiveRegionMode.Polite }, color = if (it.ok) PcColors.MintDeep else PcColors.Ink) }
    }
}

private val HINDI = "\u0939\u093F\u0928\u094D\u0926\u0940"

@Composable
private fun PersonaBody(p: PersonaState, cb: StepCallbacks) {
    Card {
        Text("Language", style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
        SegmentedSwitch(listOf("English", HINDI), if (p.language == ReplyLanguage.HINDI) 1 else 0, { cb.setLanguage(if (it == 1) ReplyLanguage.HINDI else ReplyLanguage.ENGLISH) })
        Text("Persona", style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
        SegmentedSwitch(PersonaStyle.entries.map { it.label }, p.style.ordinal, { cb.setStyle(PersonaStyle.entries[it]) })
    }
}

/** Read-only, from the same defaults the glasses use. */
fun gestureLines(map: Map<Gesture, GestureAction> = GestureDefaults.map): List<String> = buildList {
    fun a(g: Gesture) = map[g]
    a(Gesture.TAP)?.takeIf { it == GestureAction.ASK }?.let { add("Tap \u2014 Ask") }
    a(Gesture.DOUBLE_TAP)?.takeIf { it == GestureAction.PAUSE_RESUME }?.let { add("Double-tap \u2014 Pause / resume") }
    if (a(Gesture.SWIPE_FORWARD) == GestureAction.NEXT_PAGE || a(Gesture.SWIPE_BACK) == GestureAction.PREV_PAGE) add("Swipe \u2014 Next / previous page")
    add("Long-press \u2014 Hi Rokid (reserved)")
}

@Composable
private fun GesturesBody() {
    Card {
        gestureLines().forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = PcColors.Ink) }
        Text("Change these in Settings \u203A Glasses", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
    }
}
