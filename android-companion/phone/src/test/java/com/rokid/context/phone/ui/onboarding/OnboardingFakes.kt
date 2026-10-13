package com.rokid.context.phone.ui.onboarding

import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.TestResult
import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.voice.PackDownload
import com.rokid.context.phone.voice.VoiceControls
import com.rokid.context.prompt.InMemoryPersonaSettings
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeVoiceControls(initial: VoiceCapabilities = VoiceCapabilities.UNKNOWN) : VoiceControls {
    override val capabilities = MutableStateFlow(initial)
    override val settings: StateFlow<VoiceSettings> = MutableStateFlow(VoiceSettings())
    var rechecks = 0
    var downloads = 0
    var speechSettingsOpened = 0
    var downloadResult = PackDownload.DONE
    override fun setLanguage(language: SpeechLanguage) = Unit
    override fun setPhoneMic(on: Boolean) = Unit
    override fun recheck() { rechecks++ }
    override suspend fun downloadPack(): PackDownload { downloads++; return downloadResult }
    override fun openSpeechSettings() { speechSettingsOpened++ }
}

fun caps(glasses: VoiceStatus, phone: VoiceStatus = VoiceStatus.SUPPORTED, canDownload: Boolean = false) =
    VoiceCapabilities(glasses, phone, canDownload)

class FakeOnboardingAi(var seed: SeedEndpoint? = null) : OnboardingAi {
    var nextResult: Result<TestResult> = Result.success(TestResult(true, 420, "OK"))
    val drafts = mutableListOf<EndpointDraft>()
    val used = mutableListOf<String>()
    val chosen = mutableListOf<Boolean>()
    var seedTests = 0
    override suspend fun seeded(): SeedEndpoint? = seed
    override suspend fun testSeed(seed: SeedEndpoint): TestResult { seedTests++; return nextResult.getOrThrow() }
    override suspend fun testDraft(draft: EndpointDraft): Result<TestResult> { drafts += draft; return nextResult }
    override suspend fun use(endpointId: String, chosen: Boolean) { used += endpointId; this.chosen += chosen }
}

class Env(
    val link: MutableStateFlow<LinkState> = MutableStateFlow(LinkState.NOT_AUTHORIZED),
    val voice: FakeVoiceControls = FakeVoiceControls(),
    val ai: FakeOnboardingAi = FakeOnboardingAi(),
    val persona: InMemoryPersonaSettings = InMemoryPersonaSettings(),
    sdk: Int = 34,
) {
    var authorized = 0
    var appSettings = 0
    var completed = 0
    val deps = OnboardingDeps(
        link = link, voice = voice, authorize = { authorized++ }, openAppSettings = { appSettings++ },
        ai = ai, persona = { persona }, markComplete = { completed++ }, sdk = sdk,
    )
}
