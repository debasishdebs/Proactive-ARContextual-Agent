package com.rokid.context.phone.ui.onboarding

import android.os.Build
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointRules
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.ai.TestResult
import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.voice.VoiceControls
import com.rokid.context.prompt.PersonaSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** An endpoint that already exists (seeded from the legacy config or added earlier): offered as "Use <name>". */
data class SeedEndpoint(val id: String, val name: String, val model: String)

enum class AiPreset(val label: String, val kind: EndpointKind, val hasKey: Boolean, val defaultModel: String, val urlHint: String) {
    AZURE("Azure OpenAI", EndpointKind.AZURE_OPENAI, true, "", "https://your-resource.openai.azure.com"),
    OPENAI("OpenAI-compatible", EndpointKind.OPENAI_COMPAT, true, "gpt-4o-mini", "https://api.openai.com/v1"),
    OLLAMA("Ollama (on your network)", EndpointKind.OLLAMA, false, "llama3.2", "http://mybox.local:11434"),
}

/** What the user typed. [baseUrl] is already normalised by [com.rokid.context.ai.UrlNormalizer]. */
data class EndpointDraft(val preset: AiPreset, val baseUrl: String, val apiVersion: String?, val model: String, val apiKey: String?)

/** The AI step's seam over [AiGraph]: the key goes straight to the vault, never into Room, state or logs. */
interface OnboardingAi {
    suspend fun seeded(): SeedEndpoint?
    suspend fun testSeed(seed: SeedEndpoint): TestResult
    /** Saves the draft (validation failure = the failure) and runs Test on it. */
    suspend fun testDraft(draft: EndpointDraft): Result<TestResult>
    /** Binds every still-unbound chat role to the tested endpoint. */
    suspend fun use(endpointId: String)
}

class AiGraphOnboardingAi(private val ai: () -> AiGraph) : OnboardingAi {
    override suspend fun seeded(): SeedEndpoint? =
        ai().endpoints.all().firstOrNull { Capability.CHAT in it.capabilities && it.models.isNotEmpty() && it.kind != EndpointKind.LLAMACPP_LOCAL }
            ?.let { SeedEndpoint(it.id, it.name, it.models.first()) }

    override suspend fun testSeed(seed: SeedEndpoint): TestResult = ai().tester.test(seed.id, seed.model)

    override suspend fun testDraft(draft: EndpointDraft): Result<TestResult> = runCatching {
        val g = ai()
        val id = "onboarding_" + draft.preset.name.lowercase()
        val caps = setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA).takeIf { draft.preset != AiPreset.OLLAMA } ?: setOf(Capability.CHAT)
        val e = Endpoint(id, draft.preset.label, draft.preset.kind, draft.baseUrl, draft.apiVersion, listOf(draft.model), caps, System.currentTimeMillis())
        val key = if (draft.preset.kind in EndpointRules.KEYLESS) null else draft.apiKey
        g.endpoints.save(e, key).getOrThrow()
        g.tester.test(id, draft.model)
    }

    override suspend fun use(endpointId: String) {
        val g = ai()
        val e = g.endpoints.byId(endpointId) ?: return
        val model = e.models.firstOrNull() ?: return
        for (role in Role.entries) {
            if (role == Role.EMBED || role.requiredCapability !in e.capabilities) continue
            if (g.bindings.get(role) != null) continue
            g.bindings.set(RoleBinding(role, e.id, model)).getOrThrow()
        }
    }
}

/** Everything the wizard needs, as seams (tests build it from fakes; [from] wires the app). */
class OnboardingDeps(
    val link: Flow<LinkState>,
    val voice: VoiceControls,
    val authorize: () -> Unit,
    val openAppSettings: () -> Unit,
    val ai: OnboardingAi,
    /** Resolved lazily, off Main (building the AI graph reads prefs). */
    val persona: () -> PersonaSettings,
    /** Durable and off Main when it is the real one. */
    val markComplete: suspend () -> Unit,
    val sdk: Int = Build.VERSION.SDK_INT,
) {
    companion object {
        fun from(deps: PhoneDeps): OnboardingDeps {
            val s = deps.settings
            return OnboardingDeps(
                link = deps.glasses.status.map { it.link }.distinctUntilChanged(),
                voice = s.voice,
                authorize = s.reauthorize,
                openAppSettings = s.openAppSettings,
                ai = AiGraphOnboardingAi { s.ai },
                persona = { s.ai.persona },
                markComplete = { deps.onboarding.markCompleteDurably() },
            )
        }
    }
}
