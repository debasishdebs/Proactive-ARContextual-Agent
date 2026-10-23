package com.rokid.context.canvas.helper

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Helper protocol v1 (`helperProtocol = 1`, separate from the glasses link `PROTOCOL_VERSION`). Numbers mirror tools/canvas-helper/protocol/constants.json. */
object HelperProtocol {
    const val HELPER_PROTOCOL = 1
    const val JOB_TYPE_PY_EXEC = "py_exec"
    const val PAIR_CODE_TTL_MS = 120_000L
    const val POLL_INTERVAL_IDLE_MS = 3_000L
    const val HEARTBEAT_INTERVAL_MS = 1_000L
    const val HEARTBEAT_REQUEST_TIMEOUT_MS = 1_000L
    const val LEASE_MS = 10_000L
    const val HELPER_SELF_KILL_MS = 3_500L
    const val WATCHDOG_TICK_MS = 100L
    const val SCHEDULING_SLACK_MS = 300L
    const val KILL_BUDGET_MS = 1_000L
    const val MAX_KILL_LATENCY_MS = 5_000L
    const val READY_STALE_MS = 15_000L
    const val MAX_REQUEST_BODY_BYTES = 1_048_576L
    const val MAX_PAYLOAD_BYTES = 1_048_576L
    const val MAX_RESULT_BYTES = 2_097_152L
    const val PY_SOURCE_MAX_CHARS = 16_000
    const val PY_ITEMS_MIN = 1
    const val PY_ITEMS_MAX = 50
    const val PY_PER_ITEM_MS = 5_000
    const val PY_JOB_MS = 120_000
    const val PY_ENTRY = "run"
    const val MAX_INSTANCE_DEPTH = 8
    const val PY_EXEC_DEADLINE_MS = 130_000L
    const val CONFIRM_TTL_MS = 120_000L
    const val QUEUE_WAIT_MS = 30_000L
    const val EXEC_WAIT_MS = 135_000L
    const val MAX_ACTIVE_JOBS = 1
    const val CLEANUP_RETENTION_DAYS = 30
    const val HELPER_ARTIFACT_RETENTION_DAYS = 7
    val JOB_ID_REGEX = Regex("^[A-Za-z0-9:_-]{1,64}$")
    val ITEM_ID_REGEX = Regex("^i(0|[1-9][0-9]{0,1})$")
    val FAIL_CODES = listOf("needs_runtime", "timeout", "oom", "runtime_error", "killed", "bad_output", "too_large", "resources_busy", "disk_low")
    val ITEM_CODES = listOf("timeout", "schema_mismatch", "exception", "oom", "too_big")
    val DENIAL_CODES = setOf(
        "unauthorized", "capability_revoked", "not_found", "lease_expired", "voided", "cancelled", "stale_generation", "deadline_exceeded",
        "protocol_mismatch", "not_confirmed", "too_large", "invalid_result", "refused", "bad_request",
    )
    /** After one of these the helper deletes every laptop copy of the job at once and posts no result. */
    val DELETION_CLASS_DENIALS = listOf("unauthorized", "capability_revoked", "not_found", "voided", "cancelled", "not_confirmed")

    private val PAYLOAD_KEYS = setOf("helperProtocol", "jobType", "source", "entry", "items", "outSchema", "limits")
    private val RESULT_KEYS = setOf("helperProtocol", "jobType", "outputs", "stats")

    /**
     * Shape check of a `py_exec` payload (the phone refuses a bad one before the job is created; the helper may run the same check). Item ids are opaque
     * indices `i0`.. (never frame ids). Errors are fixed texts, never an echo of the content. Empty = valid.
     */
    fun validatePayload(payload: JsonElement): List<String> {
        val errs = LinkedHashSet<String>()
        if (payload !is JsonObject) return listOf("payload must be an object")
        if (payload.keys != PAYLOAD_KEYS) errs += "payload fields are not exactly the protocol fields"
        if (int(payload["helperProtocol"]) != HELPER_PROTOCOL.toLong()) errs += "helperProtocol must be 1"
        if (text(payload["jobType"]) != JOB_TYPE_PY_EXEC) errs += "jobType must be py_exec"
        val source = text(payload["source"])
        if (source == null) errs += "source must be a string" else if (source.codePointCount(0, source.length) > PY_SOURCE_MAX_CHARS) errs += "source too long"
        if (text(payload["entry"]) != PY_ENTRY) errs += "entry must be run"
        val items = payload["items"]
        if (items !is JsonArray) errs += "items must be a list" else {
            if (items.size < PY_ITEMS_MIN || items.size > PY_ITEMS_MAX) errs += "item count out of range"
            val ids = HashSet<String>()
            for (it in items) {
                val o = it as? JsonObject
                if (o == null || o.keys != setOf("id", "input")) { errs += "item must be {id,input}"; continue }
                val id = text(o["id"])
                if (id == null || !ITEM_ID_REGEX.matches(id)) errs += "item id must be an opaque index" else if (!ids.add(id)) errs += "item ids must be unique"
            }
        }
        val schema = payload["outSchema"]
        if (schema == null) errs += "outSchema missing" else errs += SchemaSubset.validateContract(schema)
        val limits = payload["limits"] as? JsonObject
        if (limits == null || limits.keys != setOf("perItemMs", "jobMs")) errs += "limits must be {perItemMs,jobMs}" else {
            val a = int(limits["perItemMs"]); val b = int(limits["jobMs"])
            if (a == null || a < 1 || a > PY_PER_ITEM_MS || b == null || b < 1 || b > PY_JOB_MS) errs += "limits out of range"
        }
        if (errs.isEmpty() && BundleHash.canonical(payload).toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) errs += "payload too large"
        return errs.toList()
    }

    /**
     * Shape check of a `py_exec` result against its (valid) [payload]: every payload item id exactly once, an ok item has an output that validates against
     * `outSchema`, a failed item has a code of [ITEM_CODES]. A refusal here is 422 `invalid_result`. Empty = valid.
     */
    fun validateResult(payload: JsonElement, result: JsonElement): List<String> {
        val errs = LinkedHashSet<String>()
        val p = payload as? JsonObject ?: return listOf("payload must be an object")
        if (result !is JsonObject) return listOf("result must be an object")
        if (result.keys != RESULT_KEYS) errs += "result fields are not exactly the protocol fields"
        if (int(result["helperProtocol"]) != HELPER_PROTOCOL.toLong()) errs += "helperProtocol must be 1"
        if (text(result["jobType"]) != JOB_TYPE_PY_EXEC) errs += "jobType must be py_exec"
        val ms = ((result["stats"] as? JsonObject)?.get("ms"))
        if (int(ms).let { it == null || it < 0 }) errs += "stats.ms must be a non-negative integer"
        val want = (p["items"] as? JsonArray)?.mapNotNull { text((it as? JsonObject)?.get("id")) }?.toSet().orEmpty()
        val outputs = result["outputs"]
        if (outputs !is JsonArray) { errs += "outputs must be a list"; return errs.toList() }
        val seen = HashSet<String>()
        for (o in outputs) {
            val obj = o as? JsonObject
            val id = text(obj?.get("id"))
            if (obj == null || id == null) { errs += "output must carry an id"; continue }
            if (id !in want) errs += "output for an unknown item"
            if (!seen.add(id)) errs += "item id appears twice"
            val ok = (obj["ok"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            when (ok) {
                "true" -> {
                    if (obj.keys != setOf("id", "ok", "output")) errs += "ok item must be {id,ok,output}"
                    else if (p["outSchema"]?.let { SchemaSubset.validate(it, obj.getValue("output")) } != true) errs += "output does not match outSchema"
                }
                "false" -> {
                    if (obj.keys != setOf("id", "ok", "code")) errs += "failed item must be {id,ok,code}"
                    else if (text(obj["code"]) !in ITEM_CODES) errs += "unknown item code"
                }
                else -> errs += "ok must be a boolean"
            }
        }
        if (seen != want) errs += "every payload item must appear exactly once"
        return errs.toList()
    }

    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun int(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
}
