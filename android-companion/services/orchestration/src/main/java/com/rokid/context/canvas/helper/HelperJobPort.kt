package com.rokid.context.canvas.helper

import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperFence
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

data class PyItem(val id: String, val input: JsonElement)

/** One `py_exec` request. [frameIds] are the authoritative ids from PhotoSource (never sent to the helper); [fence] was captured before the inputs loaded. */
class PyRequest(
    val source: String,
    val items: List<PyItem>,
    val outSchema: JsonElement,
    val frameIds: List<String>,
    val fence: HelperFence,
    val perItemMs: Int = 5000,
    val jobMs: Int = 120000,
) {
    /** The wire payload the helper receives (item ids are opaque; frame ids never appear). `bundleHash` = [BundleHash.of] of exactly this object, immutable after insert. */
    fun payload(): JsonObject = JsonObject(mapOf(
        "helperProtocol" to JsonPrimitive(HelperProtocol.HELPER_PROTOCOL),
        "jobType" to JsonPrimitive(HelperProtocol.JOB_TYPE_PY_EXEC),
        "source" to JsonPrimitive(source),
        "entry" to JsonPrimitive(HelperProtocol.PY_ENTRY),
        "items" to JsonArray(items.map { JsonObject(mapOf("id" to JsonPrimitive(it.id), "input" to it.input)) }),
        "outSchema" to outSchema,
        "limits" to JsonObject(mapOf("perItemMs" to JsonPrimitive(perItemMs), "jobMs" to JsonPrimitive(jobMs))),
    ))
}

data class PyItemOutput(val id: String, val ok: Boolean, val output: JsonElement?, val code: String?)

sealed class PyOutcome {
    data class Done(val outputs: List<PyItemOutput>, val ms: Long) : PyOutcome()
    data class Failed(val code: String) : PyOutcome()
    data class Refused(val reason: String) : PyOutcome()
    object NotConfirmed : PyOutcome()
    object Voided : PyOutcome()
    object Cancelled : PyOutcome()
    object NoHelper : PyOutcome()
}

enum class ConfirmResult { CONFIRMED, DUPLICATE, NOT_FOUND, NOT_AWAITING, EXPIRED, MISMATCH, STALE_FENCE }

data class PendingConfirmation(val jobId: String, val bundleHash: String, val sessionGen: Long, val expiresAtMs: Long, val itemCount: Int, val sourceChars: Int)

/**
 * The in-process port between the experiment executor and the job service (modelled on ReplayService). [runPy] submits, waits for the terminal outcome and, when
 * its caller is cancelled, cancels the job. Every CORE `py_exec` job is created in AWAITING_CONFIRM and leaves it only by [confirm] (one native tap bound to one
 * [ConfirmBinding]), [declineConfirm], expiry, cancel or void. The port never sees the HMAC key: the `:phone` adapter mints and verifies the tag, then calls [confirm].
 */
interface HelperJobPort {
    suspend fun runPy(req: PyRequest): PyOutcome
    suspend fun awaitingConfirm(jobId: String): Boolean
    suspend fun confirm(jobId: String, binding: ConfirmBinding): ConfirmResult
    suspend fun declineConfirm(jobId: String): Boolean
    suspend fun pendingConfirmations(): List<PendingConfirmation>
}
