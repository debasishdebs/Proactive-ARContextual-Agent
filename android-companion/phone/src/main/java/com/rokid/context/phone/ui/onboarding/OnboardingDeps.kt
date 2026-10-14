package com.rokid.context.phone.ui.onboarding

import android.os.Build
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.EndpointSeeder
import com.rokid.context.ai.EndpointRules
import com.rokid.context.ai.EnvDefaults
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

/**
 * An endpoint that already exists (seeded from the legacy config or added earlier): offered as "Use <name>".
 * [needsKey]: a kind that takes a key, with none stored (the build seed: endpoint + deployment, no key; BUG-R3-2).
 */
data class SeedEndpoint(val id: String, val name: String, val model: String, val needsKey: Boolean = false)

/** What a preset's form starts with. BUG-R3-2: Azure gets the build's endpoint, API version and deployment. */
data class PresetDefaults(val url: String = "", val apiVersion: String = "", val model: String = "") {
    companion object {
        /** Azure is prefilled only when the build has an endpoint; every other preset starts empty with its model. */
        fun from(p: AiPreset, azureEndpoint: String = EnvDefaults.azureEndpoint, azureDeployment: String = EnvDefaults.azureDeployment) =
            if (p == AiPreset.AZURE && azureEndpoint.isNotBlank()) PresetDefaults(azureEndpoint, EnvDefaults.AZURE_API_VERSION, azureDeployment)
            else PresetDefaults(model = p.defaultModel)
    }
}

enum class AiPreset(val label: String, val kind: EndpointKind, val hasKey: Boolean, val defaultModel: String, val urlHint: String) {
    AZURE("Azure OpenAI", EndpointKind.AZURE_OPENAI, true, "", "https://your-resource.openai.azure.com"),
    OPENAI("OpenAI-compatible", EndpointKind.OPENAI_COMPAT, true, "gpt-4o-mini", "https://api.openai.com/v1"),
    OLLAMA("Ollama (on your network)", EndpointKind.OLLAMA, false, "llama3.2", "http://mybox.local:11434");
    val endpointId: String get() = ID_PREFIX + name.lowercase()
    companion object { const val ID_PREFIX = "onboarding_" }
}

/** What the user typed. [baseUrl] is already normalised by [com.rokid.context.ai.UrlNormalizer]. */
data class EndpointDraft(val preset: AiPreset, val baseUrl: String, val apiVersion: String?, val model: String, val apiKey: String?) {
    /** Never prints the key. */
    override fun toString() = "EndpointDraft(preset=$preset, url=$baseUrl, model=$model, apiKey=${if (apiKey == null) "none" else "***"})"
}

/** The AI step's seam over [AiGraph]: the key goes straight to the vault, never into Room, state or logs. */
interface OnboardingAi {
    suspend fun seeded(): SeedEndpoint?
    /** [apiKey] (typed for a [SeedEndpoint.needsKey] seed) is stored in the vault first, through EndpointRepository.save. */
    suspend fun testSeed(seed: SeedEndpoint, apiKey: String? = null): TestResult
    /** Saves the draft (validation failure = the failure) and runs Test on it. */
    suspend fun testDraft(draft: EndpointDraft): Result<TestResult>
    /**
     * Binds the chat roles to the tested endpoint. Unbound roles always; with [chosen] (the user picked and tested a
     * preset) also every role still on a binding nobody edited ([AiGraphOnboardingAi.isUntouched]), so the choice wins
     * over the seeder (final review I1). A binding the user changed in Settings is never replaced.
     */
    suspend fun use(endpointId: String, chosen: Boolean = false)
}

class AiGraphOnboardingAi(private val ai: () -> AiGraph) : OnboardingAi {
    override suspend fun seeded(): SeedEndpoint? {
        val g = ai()
        val e = g.endpoints.all().firstOrNull { Capability.CHAT in it.capabilities && it.models.isNotEmpty() && it.kind != EndpointKind.LLAMACPP_LOCAL }
            ?: return null
        val needsKey = e.kind !in EndpointRules.KEYLESS && g.endpoints.keyFor(e).isNullOrBlank()
        return SeedEndpoint(e.id, e.name, e.models.first(), needsKey)
    }

    override suspend fun testSeed(seed: SeedEndpoint, apiKey: String?): TestResult {
        val g = ai()
        if (!apiKey.isNullOrBlank()) {
            val e = g.endpoints.byId(seed.id) ?: return TestResult(false, 0, "Endpoint not found")
            g.endpoints.save(e, apiKey).getOrThrow()
        }
        return g.tester.test(seed.id, seed.model)
    }

    override suspend fun testDraft(draft: EndpointDraft): Result<TestResult> = runCatching {
        val g = ai()
        val id = draft.preset.endpointId
        val caps = setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA).takeIf { draft.preset != AiPreset.OLLAMA } ?: setOf(Capability.CHAT)
        val e = Endpoint(id, draft.preset.label, draft.preset.kind, draft.baseUrl, draft.apiVersion, listOf(draft.model), caps, System.currentTimeMillis())
        val key = if (draft.preset.kind in EndpointRules.KEYLESS) null else draft.apiKey
        g.endpoints.save(e, key).getOrThrow()
        g.tester.test(id, draft.model)
    }

    override suspend fun use(endpointId: String, chosen: Boolean) {
        val g = ai()
        val e = g.endpoints.byId(endpointId) ?: return
        val model = e.models.firstOrNull() ?: return
        for (role in Role.entries) {
            if (role == Role.EMBED || role.requiredCapability !in e.capabilities) continue
            val current = g.bindings.get(role)
            if (current != null && !(chosen && current.endpointId != e.id && isUntouched(current, g))) continue
            g.bindings.set(RoleBinding(role, e.id, model)).getOrThrow()
        }
    }

    /**
     * True for a binding exactly as [com.rokid.context.ai.EndpointSeeder] or an earlier wizard run wrote it: on a seeded
     * or `onboarding_` endpoint, with that endpoint's first model, no temperature/max-tokens, and no fallback other
     * than the seeder's on-device one. Settings edits change at least one of those, so they are kept.
     */
    private suspend fun isUntouched(b: RoleBinding, g: AiGraph): Boolean {
        val wizardOwned = b.endpointId in SEEDED_IDS || b.endpointId.startsWith(AiPreset.ID_PREFIX)
        if (!wizardOwned || b.temperature != null || b.maxTokens != null) return false
        if (b.model != g.endpoints.byId(b.endpointId)?.models?.firstOrNull()) return false
        val fb = b.fallbackEndpointId ?: return b.fallbackModel == null
        return fb == EndpointSeeder.LOCAL_ID && b.fallbackModel == g.endpoints.byId(fb)?.models?.firstOrNull()
    }

    private companion object {
        val SEEDED_IDS = setOf(EndpointSeeder.AZURE_ID, EndpointSeeder.OLLAMA_ID, EndpointSeeder.LOCAL_ID)
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
    /** What a preset's form starts with (the build defaults in the app). */
    val presetDefaults: (AiPreset) -> PresetDefaults = { PresetDefaults.from(it) },
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
