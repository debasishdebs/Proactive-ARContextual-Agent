package com.rokid.context.canvas.helper

import com.rokid.context.canvas.helper.ProtocolFixtures.arr
import com.rokid.context.canvas.helper.ProtocolFixtures.i
import com.rokid.context.canvas.helper.ProtocolFixtures.load
import com.rokid.context.canvas.helper.ProtocolFixtures.num
import com.rokid.context.canvas.helper.ProtocolFixtures.o
import com.rokid.context.canvas.helper.ProtocolFixtures.obj
import com.rokid.context.canvas.helper.ProtocolFixtures.str
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.PcLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** WP1 RED group: the Kotlin side of the shared fixtures. The Python side reads the same files (tools/canvas-helper/tests/test_protocol_fixtures.py). */
class HelperProtocolFixturesTest {
    private val hashVectors = load("hash-vectors.json").arr("vectors").map { it.o() }

    @Test fun folderHoldsExactlyTheSevenPythonOnlyFiles() {
        assertEquals(ProtocolFixtures.SEVEN.sorted(), ProtocolFixtures.dir.list()!!.sorted())
    }

    @Test fun hashVectorsKeyOrder() {
        val v = hashVectors.first { it.str("name") == "keyOrder" }
        assertEquals(v.str("canonical"), BundleHash.canonical(v.getValue("payload")))
        assertEquals(v.str("sha256"), BundleHash.of(v.getValue("payload")))
    }

    @Test fun hashVectorsUnicodeAndCodePointOrder() {
        for (n in listOf("unicode", "codePointKeyOrder")) {
            val v = hashVectors.first { it.str("name") == n }
            assertEquals(n, v.str("canonical"), BundleHash.canonical(v.getValue("payload")))
            assertEquals(n, v.str("sha256"), BundleHash.of(v.getValue("payload")))
        }
    }

    @Test fun everyHashVectorMatches() {
        assertTrue(hashVectors.size >= 6)
        for (v in hashVectors) assertEquals(v.str("name"), v.str("sha256"), BundleHash.of(v.getValue("payload")))
    }

    @Test fun hashRefusesFloatsBecauseCanonicalNumbersAreIntegersOnly() {
        try { BundleHash.canonical(JsonPrimitive(1.5)); fail("float accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun constantsJsonMatchesKotlinConstants() {
        val c = load("constants.json")
        assertEquals(c.i("helperProtocol"), HelperProtocol.HELPER_PROTOCOL)
        assertEquals(c.num("pairCodeTtlMs"), HelperProtocol.PAIR_CODE_TTL_MS)
        assertEquals(c.num("pollIntervalIdleMs"), HelperProtocol.POLL_INTERVAL_IDLE_MS)
        assertEquals(c.num("heartbeatIntervalMs"), HelperProtocol.HEARTBEAT_INTERVAL_MS)
        assertEquals(c.num("heartbeatRequestTimeoutMs"), HelperProtocol.HEARTBEAT_REQUEST_TIMEOUT_MS)
        assertEquals(c.num("leaseMs"), HelperProtocol.LEASE_MS)
        assertEquals(c.num("helperSelfKillMs"), HelperProtocol.HELPER_SELF_KILL_MS)
        assertEquals(c.num("watchdogTickMs"), HelperProtocol.WATCHDOG_TICK_MS)
        assertEquals(c.num("schedulingSlackMs"), HelperProtocol.SCHEDULING_SLACK_MS)
        assertEquals(c.num("killBudgetMs"), HelperProtocol.KILL_BUDGET_MS)
        assertEquals(c.num("maxKillLatencyMs"), HelperProtocol.MAX_KILL_LATENCY_MS)
        assertEquals(c.num("readyStaleMs"), HelperProtocol.READY_STALE_MS)
        assertEquals(c.num("maxRequestBodyBytes"), HelperProtocol.MAX_REQUEST_BODY_BYTES)
        assertEquals(c.num("maxPayloadBytes"), HelperProtocol.MAX_PAYLOAD_BYTES)
        assertEquals(c.num("maxResultBytes"), HelperProtocol.MAX_RESULT_BYTES)
        val py = c.obj("py")
        assertEquals(py.i("sourceMaxChars"), HelperProtocol.PY_SOURCE_MAX_CHARS)
        assertEquals(py.i("itemsMin"), HelperProtocol.PY_ITEMS_MIN)
        assertEquals(py.i("itemsMax"), HelperProtocol.PY_ITEMS_MAX)
        assertEquals(py.i("perItemMs"), HelperProtocol.PY_PER_ITEM_MS)
        assertEquals(py.i("jobMs"), HelperProtocol.PY_JOB_MS)
        assertEquals(py.i("maxInstanceDepth"), HelperProtocol.MAX_INSTANCE_DEPTH)
        assertEquals(c.num("pyExecDeadlineMs"), HelperProtocol.PY_EXEC_DEADLINE_MS)
        assertEquals(c.num("confirmTtlMs"), HelperProtocol.CONFIRM_TTL_MS)
        assertEquals(c.num("queueWaitMs"), HelperProtocol.QUEUE_WAIT_MS)
        assertEquals(c.num("execWaitMs"), HelperProtocol.EXEC_WAIT_MS)
        assertEquals(c.i("maxActiveJobs"), HelperProtocol.MAX_ACTIVE_JOBS)
        assertEquals(c.i("cleanupRetentionDays"), HelperProtocol.CLEANUP_RETENTION_DAYS)
        assertEquals(c.i("helperArtifactRetentionDays"), HelperProtocol.HELPER_ARTIFACT_RETENTION_DAYS)
        assertEquals(c.str("jobIdRegex"), HelperProtocol.JOB_ID_REGEX.pattern)
        assertEquals(c.arr("failCodes").map { it.jsonPrimitive.content }, HelperProtocol.FAIL_CODES)
        assertEquals(c.arr("itemCodes").map { it.jsonPrimitive.content }, HelperProtocol.ITEM_CODES)
        assertEquals(c.arr("deletionClassDenials").map { it.jsonPrimitive.content }, HelperProtocol.DELETION_CLASS_DENIALS)
        assertEquals(c.arr("jobTypes").map { it.jsonPrimitive.content }, listOf(HelperProtocol.JOB_TYPE_PY_EXEC))
        assertEquals(c.arr("schemaSubsetKeywords").map { it.jsonPrimitive.content }.toSet(), SchemaSubset.KEYWORDS)
        assertEquals(c.str("confirmMessageFormat"), "jobId|bundleHash|sessionGen|expiresAtMs")
        val denials = c.obj("denials").flatMap { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }.toSet()
        assertEquals(denials, HelperProtocol.DENIAL_CODES)
        assertTrue("not_confirmed" in denials)
    }

    @Test fun timingArithmeticAndThreeWaits() {
        assertEquals(4900L, HelperProtocol.HELPER_SELF_KILL_MS + HelperProtocol.WATCHDOG_TICK_MS + HelperProtocol.SCHEDULING_SLACK_MS + HelperProtocol.KILL_BUDGET_MS)
        assertTrue(4900L <= HelperProtocol.MAX_KILL_LATENCY_MS && HelperProtocol.MAX_KILL_LATENCY_MS < HelperProtocol.LEASE_MS)
        assertEquals(HelperProtocol.PY_EXEC_DEADLINE_MS + 5000, HelperProtocol.EXEC_WAIT_MS)
        assertEquals(120000L, HelperProtocol.CONFIRM_TTL_MS)
        assertEquals(30000L, HelperProtocol.QUEUE_WAIT_MS)
        assertEquals(50, HelperProtocol.PY_ITEMS_MAX)
    }

    @Test fun protocolVersionOfTheGlassesLinkIsNotTouched() {
        assertEquals(1, HelperProtocol.HELPER_PROTOCOL)
        assertEquals(1, com.rokid.context.core.model.PROTOCOL_VERSION)
    }

    @Test fun payloadSamplesParse() {
        val s = load("py_exec-payload-samples.json")
        for (v in s.arr("valid").map { it.o() }) assertEquals(v.str("name"), emptyList<String>(), HelperProtocol.validatePayload(v.getValue("payload")))
        val invalid = s.arr("invalid").map { it.o() }
        assertTrue(invalid.size >= 10)
        for (v in invalid) assertTrue(v.str("name"), HelperProtocol.validatePayload(v.getValue("payload")).isNotEmpty())
    }

    @Test fun resultSamplesParse() {
        val payloads = load("py_exec-payload-samples.json").arr("valid").map { it.o() }.associate { it.str("name") to it.getValue("payload") }
        val s = load("py_exec-result-samples.json")
        for (v in s.arr("valid").map { it.o() }) assertEquals(v.str("name"), emptyList<String>(), HelperProtocol.validateResult(payloads.getValue(v.str("payload")), v.getValue("result")))
        for (v in s.arr("invalid").map { it.o() }) assertTrue(v.str("name"), HelperProtocol.validateResult(payloads.getValue(v.str("payload")), v.getValue("result")).isNotEmpty())
    }

    @Test fun validationMessagesNeverEchoContent() {
        val bad = JsonObject(mapOf("helperProtocol" to JsonPrimitive(1), "source" to JsonPrimitive("SECRET-CONTENT"), "zz" to JsonPrimitive("SECRET-KEY")))
        val errs = HelperProtocol.validatePayload(bad)
        assertTrue(errs.isNotEmpty())
        assertTrue(errs.none { it.contains("SECRET") })
    }

    @Test fun frozenSchemaRows() {
        val rows = load("schema-subset-rows.json")
        val inst = rows.arr("instanceRows").map { it.o() }
        val contracts = rows.arr("contractRows").map { it.o() }
        assertTrue(inst.size + contracts.size >= 30)
        for (r in inst) assertEquals(r.str("id"), r.getValue("valid").jsonPrimitive.booleanOrNull, SchemaSubset.validate(r.getValue("schema"), r.getValue("instance")))
        for (r in contracts) assertEquals(r.str("id"), r.getValue("supported").jsonPrimitive.booleanOrNull!!, SchemaSubset.validateContract(r.getValue("schema")).isEmpty())
    }

    @Test fun schemaSubsetKeywordsAreCoveredPositivelyAndNegatively() {
        val inst = load("schema-subset-rows.json").arr("instanceRows").map { it.o() }
        fun uses(schema: kotlinx.serialization.json.JsonElement, k: String): Boolean = when (schema) {
            is JsonObject -> schema.containsKey(k) || schema.values.any { uses(it, k) }
            is JsonArray -> schema.any { uses(it, k) }
            else -> false
        }
        for (k in SchemaSubset.KEYWORDS - setOf("properties", "additionalProperties", "required")) {
            assertTrue("$k positive", inst.any { uses(it.getValue("schema"), k) && it.getValue("valid").jsonPrimitive.booleanOrNull == true })
            assertTrue("$k negative", inst.any { uses(it.getValue("schema"), k) && it.getValue("valid").jsonPrimitive.booleanOrNull == false })
        }
        for (k in listOf("properties", "additionalProperties", "required")) assertTrue(k, inst.any { uses(it.getValue("schema"), k) })
    }

    @Test fun patternKeywordIsRefusedBeforeAJobIsCreated() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("string"), "pattern" to JsonPrimitive("^a$")))
        assertTrue(SchemaSubset.validateContract(schema).isNotEmpty())
    }

    @Test fun jobIdRegexVectors() {
        val d = load("job-id.json")
        assertEquals(d.str("regex"), HelperProtocol.JOB_ID_REGEX.pattern)
        for (s in d.arr("valid")) assertTrue(s.jsonPrimitive.content, HelperProtocol.JOB_ID_REGEX.matches(s.jsonPrimitive.content))
        for (s in d.arr("invalid")) assertFalse(s.jsonPrimitive.content, HelperProtocol.JOB_ID_REGEX.matches(s.jsonPrimitive.content))
    }

    @Test fun noKeywordOrClassNameDenylistInTheFixtures() {
        // c5 #4: generation-only fixtures (tool id, class name, keyword denylist, generated paths) are v2, not CORE.
        for (n in ProtocolFixtures.SEVEN) {
            val t = java.io.File(ProtocolFixtures.dir, n).readText()
            assertFalse(n, t.contains("denylist", ignoreCase = true) && !t.contains("\"_note\""))
            assertFalse(n, t.contains("toolIdRegex") || t.contains("generated-files"))
        }
    }

    @Test fun canvasLogSinkIsTheMergedLogPortAndNeedsNoAndroid() {
        val lines = ArrayList<String>()
        val sink: CanvasLogSink = com.rokid.context.core.log.LogPort { _, tag, msg -> lines += "$tag $msg" }
        sink.log(Level.I, "canvas", "n=3")
        assertEquals(listOf("canvas n=3"), lines)
        val gated: CanvasLogSink = PcLog.port("canvas") // the production binding: the gated adapter, never a concrete sink
        assertNotNull(gated)
        val build = java.io.File(ProtocolFixtures.dir, "../../../android-companion/core/model/build.gradle.kts").readText()
        assertFalse("core:model must stay JVM-only", build.contains("com.android"))
    }
}
