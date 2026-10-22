package com.rokid.context.capture.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class PlanJsonTest {
    @Test fun configHashIgnoresMeta() {
        val a = PlanFixtures.plan(name = "one")
        val b = PlanFixtures.plan(meta = PlanMeta("two", 99, mapOf("REST" to 1.5)))
        assertEquals(a.configHash, b.configHash)
        assertEquals(12, a.configHash.length)
        assertTrue(a.configHash.all { it in "0123456789abcdef" })
        val changed = PlanFixtures.row(a.config, PlanState.REST) { it.copy(capPerMin = 3) }
        assertNotEquals(a.configHash, PlanJson.configHash(changed))
    }

    @Test fun configHashStableAcrossKeyOrder() {
        val c = PlanResolver.identity()
        val reversed = JsonObject(PlanJson.configJson(c).entries.reversed().associate { it.key to it.value })
        assertEquals(PlanJson.configHash(c), CanonicalJson.encode(reversed).let { s ->
            java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
        })
        // a pretty-printed text with reversed top-level keys decodes to the same hash
        val raw = PlanFixtures.raw()
        val rev = JsonObject(raw.entries.reversed().associate { it.key to it.value })
        val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), rev)
        assertEquals(PlanJson.configHash(c), PlanJson.configHash(PlanJson.decodeStrict(pretty).getOrThrow().config))
    }

    @Test fun strictDecodeRejectsUnknownKey() {
        val t = PlanFixtures.text()
        assertTrue(PlanJson.decodeStrict(t.replace("\"ctxBound\":true", "\"ctxBound\":true,\"extra\":1")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("\"nudges\":true", "\"nudges\":true,\"pause\":true")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("{\"name\"", "{\"zzz\":1,\"name\"")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("\"bridgeMs\":250", "\"bridgeMs\":NaN")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("\"onsetRadS\":0.6", "\"onsetRadS\":Infinity")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("\"glance\":", "\"glanse\":")).isFailure)
        assertTrue(PlanJson.decodeStrict(t).isSuccess)
    }

    @Test fun roundTripExact() {
        val p = PlanFixtures.plan(meta = PlanMeta("m é \"q\"", 7, mapOf("REST" to 1.25, "FLIGHT" to 0.5)))
        val t = PlanJson.encode(p)
        val back = PlanJson.decodeStrict(t).getOrThrow()
        assertEquals(p, back)
        assertEquals(t, PlanJson.encode(back))
        assertEquals(p.config.glance.minBoutRad, back.config.glance.minBoutRad)
        assertTrue(Json.parseToJsonElement(t) is JsonObject)
    }

    @Test fun duplicateKeysRefused() {
        val t = PlanFixtures.text()
        assertTrue(PlanJson.decodeStrict(t.replace("\"planSchema\":1}", "\"planSchema\":1,\"planSchema\":1}")).isFailure)
        assertTrue(PlanJson.decodeStrict(t.replace("\"bridgeMs\":250,", "\"bridgeMs\":250,\"bridgeMs\":251,")).isFailure)
        // the same key spelled with an escape is still the same key
        assertTrue(PlanJson.decodeStrict(t.replace("\"bridgeMs\":250,", "\"bridgeMs\":250,\"bridge\\u004Ds\":251,")).isFailure)
        // a key text inside a string value is not a key
        assertTrue(PlanJson.decodeStrict(PlanJson.encode(PlanFixtures.plan(name = "\"a\":1,\"a\":2"))).isSuccess)
        assertTrue(PlanJson.decodeStrict(t).isSuccess)
    }

    @Test fun numberGrammarIsJsonOnly() {
        val t = PlanFixtures.text()
        for (bad in listOf("0.6f", "0.6F", "0.6d", "0x1p-1", "+0.6", "0.6.0")) {
            assertTrue(PlanJson.decodeStrict(t.replace("\"onsetRadS\":0.6", "\"onsetRadS\":$bad")).isFailure, bad)
        }
        for (bad in listOf("250d", "0x10", "2.5e2f")) {
            assertTrue(PlanJson.decodeStrict(t.replace("\"bridgeMs\":250", "\"bridgeMs\":$bad")).isFailure, bad)
        }
        assertTrue(PlanJson.decodeStrict(t.replace("\"onsetRadS\":0.6", "\"onsetRadS\":6e-1")).isSuccess)
    }

    @Test fun identityConfigHashPinned() {
        // the same value is pinned in :services:ai CanonicalJsonParityTest
        assertEquals("f5782e0d8610", PlanJson.configHash(PlanResolver.identity()))
    }
}
