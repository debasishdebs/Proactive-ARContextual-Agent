package com.rokid.context.orchestration

import android.content.Context
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.db.PromptVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

/**
 * G19 (MVP section 23 "config export without secrets"; import stays deferred). Settings > Diagnostics > Export settings:
 * one JSON of the settings inventory (`2026-10-14-settings-inventory.md`): the restorable prefs files ([PREFS_FILES]),
 * the on-device "Allow mobile data" choice, the encryption target, endpoints, role bindings and the active prompts.
 *
 * Secrets never enter it: the key vault, the DB key, the legacy secret file and the glasses pairing token are never
 * opened; endpoints carry no key and no key reference; addresses lose user info, query and fragment (a key can ride
 * there); and any prefs key named like a secret ([SECRET_NAME]) is dropped.
 */
object ConfigExport {
    const val FILE_NAME = "proactive-context-settings.json"
    const val FORMAT = "proactive-context-settings"

    /** BackupRulesTest's restorable files minus nudge_engine (a run gate, not a setting). */
    val PREFS_FILES = listOf(
        "hub_settings", "voice_settings", "retrieval_settings", "prompt_settings", "persona_settings", "context_sources",
        "action_settings", "memory_settings",
        // Pause zones (user configuration: ids, names, boxes, buffers). Delete all never touches this file.
        "pause_zones",
    )

    private val SECRET_NAME = Regex("(?i)(api[_-]?key|token|secret|password|passphrase|credential|auth)")
    private val json = Json { prettyPrint = true }

    /** Writes into `cache/exports` (the FileProvider folder), replacing the previous settings export. */
    suspend fun write(context: Context, ai: AiGraph = AiGraph.get(context), nowMs: Long = System.currentTimeMillis()): File =
        DataOpGate.shared.export { writeLocked(context, ai, nowMs) } // the shared side of the gate: refused ("busy: a delete is running") while a delete runs

    private suspend fun writeLocked(context: Context, ai: AiGraph, nowMs: Long): File = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("exportedAt", nowMs)
            put("secretsExcluded", true)
            put("preferences", JsonObject(PREFS_FILES.associateWith { prefsFile(context, it) }))
            put("localModels", buildJsonObject {
                put("allowMobileData", context.getSharedPreferences("app_config", Context.MODE_PRIVATE).getBoolean("local_models_allow_metered", false))
            })
            put("memoryEncryption", buildJsonObject {
                val p = context.getSharedPreferences("memory_graph", Context.MODE_PRIVATE)
                put("encryptedTarget", if (p.contains("db_encrypted_target")) JsonPrimitive(p.getBoolean("db_encrypted_target", false)) else JsonNull)
            })
            put("endpoints", JsonArray(ai.endpoints.all().map(::endpoint)))
            put("roleBindings", JsonArray(ai.bindings.observeAll().first().map(::binding)))
            put("prompts", JsonArray(ai.prompts.observeAllActive().first().map(::prompt)))
        }
        val dir = MemoryExport.dir(context).apply { mkdirs() }
        File(dir, FILE_NAME).apply { writeText(json.encodeToString(JsonObject.serializer(), root)) }
    }

    private fun prefsFile(context: Context, name: String): JsonObject {
        val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
        return JsonObject(all.filterKeys { !SECRET_NAME.containsMatchIn(it) }.toSortedMap().mapValues { (_, v) -> value(v) })
    }

    private fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        // A stored JSON object (HudSettings) is embedded as JSON, everything else stays text.
        is String -> if (v.trimStart().startsWith("{")) runCatching { Json.parseToJsonElement(v) }.getOrElse { JsonPrimitive(v) } else JsonPrimitive(v)
        is Set<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) }.sortedBy { it.content })
        else -> JsonPrimitive(v.toString())
    }

    private fun endpoint(e: Endpoint) = buildJsonObject {
        put("id", e.id)
        put("name", e.name)
        put("kind", e.kind.name)
        put("baseUrl", safeUrl(e.baseUrl))
        put("apiVersion", e.apiVersion)
        put("models", JsonArray(e.models.map(::JsonPrimitive)))
        put("capabilities", JsonArray(e.capabilities.map { JsonPrimitive(it.name) }.sortedBy { it.content }))
    }

    private fun binding(b: RoleBinding) = buildJsonObject {
        put("role", b.role.name)
        put("endpointId", b.endpointId)
        put("model", b.model)
        put("temperature", b.temperature)
        put("maxTokens", b.maxTokens)
        put("fallbackEndpointId", b.fallbackEndpointId)
        put("fallbackModel", b.fallbackModel)
    }

    private fun prompt(p: PromptVersionEntity) = buildJsonObject {
        put("prompt", p.promptId)
        put("version", p.version)
        put("template", p.template)
        put("outputSchema", p.outputSchema)
    }

    /** scheme://host[:port]/path only; unparseable addresses are left out rather than risk a credential. */
    internal fun safeUrl(url: String): String? = runCatching {
        val u = URI(url.trim())
        if (u.scheme == null || u.host == null) return null
        URI(u.scheme, null, u.host, u.port, u.path, null, null).toString()
    }.getOrNull()
}
