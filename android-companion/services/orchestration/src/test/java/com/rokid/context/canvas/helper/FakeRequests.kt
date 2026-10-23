package com.rokid.context.canvas.helper

import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun fenceAt(rev: Long, epochCounter: Long = 2) = HelperFence(wipeGen = 3, deletionEpoch = DeletionEpoch("install-a", epochCounter, 0), frameForgetRev = rev)

val TEST_OUT_SCHEMA = JsonObject(mapOf(
    "type" to JsonPrimitive("object"), "required" to JsonArray(listOf(JsonPrimitive("n"))),
    "properties" to JsonObject(mapOf("n" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "minimum" to JsonPrimitive(0))))),
    "additionalProperties" to JsonPrimitive(false),
))

fun fakeRequest(fence: HelperFence = fenceAt(5), n: Int = 2) = PyRequest(
    source = "def run(x):\n    return {'n': len(x['text'])}\n",
    items = (0 until n).map { PyItem("i$it", JsonObject(mapOf("text" to JsonPrimitive("t$it")))) },
    outSchema = TEST_OUT_SCHEMA,
    frameIds = (0 until n).map { "frame-$it" },
    fence = fence,
)

fun okResult(ids: List<String>) = JsonObject(mapOf(
    "helperProtocol" to JsonPrimitive(1), "jobType" to JsonPrimitive("py_exec"),
    "outputs" to JsonArray(ids.map { JsonObject(mapOf("id" to JsonPrimitive(it), "ok" to JsonPrimitive(true), "output" to JsonObject(mapOf("n" to JsonPrimitive(1))))) }),
    "stats" to JsonObject(mapOf("ms" to JsonPrimitive(5))),
))

/** The wire form of a test result built as a [JsonObject] (integers only): the text the helper would hash and its SHA-256. Float rows give the text directly. */
fun wire(result: JsonObject): Pair<String, String> { val t = BundleHash.canonical(result); return t to BundleHash.ofCanonical(t) }

suspend fun HelperJobService.commitResult(jobId: String, helperId: String, leaseGen: Long, result: JsonObject): ResultCommit =
    wire(result).let { (t, h) -> commitResult(jobId, helperId, leaseGen, t, h) }

fun InMemoryHelperJobPort.complete(jobId: String, result: JsonObject): List<String> = wire(result).let { (t, h) -> complete(jobId, t, h) }
