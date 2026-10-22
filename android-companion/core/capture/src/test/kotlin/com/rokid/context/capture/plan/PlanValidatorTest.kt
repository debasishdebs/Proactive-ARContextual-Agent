package com.rokid.context.capture.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PlanValidatorTest {
    private val base = PlanResolver.identity()

    private fun problems(c: PlanConfig) = PlanValidator.check(PlanFixtures.text(PlanFixtures.plan(c))).first

    private fun rawProblems(f: (MutableMap<String, Any?>) -> Unit): List<String> {
        val raw = PlanFixtures.mutate(f = f)
        return PlanValidator.problems(raw, null, 100)
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any?>.at(vararg path: String): MutableMap<String, Any?> =
        path.fold(this) { m, k -> m[k] as MutableMap<String, Any?> }

    private fun gaps(c: PlanConfig, g: TimerPhotoGaps) = PlanFixtures.row(c, PlanState.REST) { it.copy(timerPhotoGapsMs = g) }

    @Test fun identityIsClean() = assertEquals(emptyList(), problems(base))

    @Test fun acceptsRestGap300s() = assertEquals(emptyList(), problems(gaps(base, TimerPhotoGaps(300_000, 300_000, 300_000, 300_000))))

    @Test fun acceptsGap5s() = assertEquals(emptyList(), problems(gaps(base, TimerPhotoGaps(5_000, 5_000, 5_000, 5_000))))

    @Test fun refusesGap301s() = assertEquals(
        listOf("policy row REST: timer photo gaps must be 0 (inherit) or 5–300 s"),
        problems(gaps(base, TimerPhotoGaps(301_000, 60_000, 30_000, 120_000))),
    )

    @Test fun refusesGap4s() = assertEquals(
        listOf("policy row REST: timer photo gaps must be 0 (inherit) or 5–300 s"),
        problems(gaps(base, TimerPhotoGaps(30_000, 4_000, 30_000, 120_000))),
    )

    @Test fun acceptsGapZeroInherit() = assertEquals(emptyList(), problems(gaps(base, TimerPhotoGaps(0, 0, 0, 0))))

    @Test fun acceptsNullCap() = assertEquals(emptyList(), problems(PlanFixtures.row(base, PlanState.REST) { it.copy(capPerMin = null) }))

    @Test fun refusesCapOutsideOneToFour() {
        for (cap in listOf(0, 5, -1)) {
            assertEquals(
                listOf("policy row REST: capPerMin must be 1–4 or unchanged"),
                problems(PlanFixtures.row(base, PlanState.REST) { it.copy(capPerMin = cap) }),
            )
        }
        for (cap in 1..4) assertEquals(emptyList(), problems(PlanFixtures.row(base, PlanState.REST) { it.copy(capPerMin = cap) }))
    }

    @Test fun refusesDrivingNudgesOn() = assertEquals(
        listOf("VEHICLE_DRIVING nudges are always off"),
        problems(PlanFixtures.row(base, PlanState.VEHICLE_DRIVING) { it.copy(nudges = true) }),
    )

    @Test fun refusesWalkingRow() {
        val p = rawProblems { it.at("config", "policy")["WALKING"] = JsonObject(emptyMap()) }
        assertTrue("policy row WALKING is locked" in p, p.toString())
    }

    @Test fun refusesUnknownRow() {
        val p = rawProblems { it.at("config", "policy")["UNKNOWN"] = JsonObject(emptyMap()) }
        assertTrue("policy row UNKNOWN is locked" in p, p.toString())
    }

    @Test fun refusesPause() {
        val p = rawProblems { it.at("config", "policy", "REST")["pause"] = JsonPrimitive(true) }
        assertTrue("pause is not allowed" in p, p.toString())
    }

    @Test fun refusesMotionWake() {
        val p = rawProblems { it.at("config")["cameraWake"] = JsonPrimitive("MOTION") }
        assertTrue("MOTION wake is settings-only" in p, p.toString())
    }

    @Test fun refusesNonTunableFusion() {
        val p = rawProblems { it.at("config", "phoneFusion")["walkingVeto"] = JsonPrimitive(1) }
        assertTrue("fusion.walkingVeto is not tunable" in p, p.toString())
    }

    @Test fun refusesFusionOutsideHalfOrFastKmhRange() {
        val d = PlanBounds.FUSION_DEFAULTS.getValue("vehicleHoldMs")
        fun withFusion(k: String, v: Double) = problems(base.copy(phoneFusion = base.phoneFusion + (k to v)))
        assertEquals(emptyList(), withFusion("vehicleHoldMs", d * 1.5))
        assertEquals(1, withFusion("vehicleHoldMs", d * 1.51).size)
        assertEquals(1, withFusion("vehicleHoldMs", d * 0.49).size)
        assertEquals(emptyList(), withFusion("fastKmh", 20.0))
        assertEquals(emptyList(), withFusion("fastKmh", 40.0))
        assertEquals(listOf("fusion.fastKmh out of bounds 20–40"), withFusion("fastKmh", 41.0))
    }

    @Test fun refusesBadSchema() {
        val raw = PlanFixtures.mutate { it["planSchema"] = JsonPrimitive(2) }
        assertTrue("plan schema 2 is not supported" in PlanValidator.problems(raw, null, 100))
    }

    @Test fun refusesGlanceAndGateOutOfBounds() {
        val g = base.glance
        assertEquals(listOf("glance.onsetRadS out of bounds 0.4–1"), problems(base.copy(glance = g.copy(onsetRadS = 1.2f))))
        assertEquals(listOf("glance.releaseRadS out of bounds 0.2–0.6"), problems(base.copy(glance = g.copy(releaseRadS = 0.7f))))
        assertEquals(listOf("glance.settleMs out of bounds 1000–3000"), problems(base.copy(glance = g.copy(settleMs = 500))))
        assertEquals(listOf("glance.bridgeMs out of bounds 100–500"), problems(base.copy(glance = g.copy(bridgeMs = 600))))
        assertEquals(listOf("gate.hamming out of bounds 6–16"), problems(base.copy(gate = base.gate.copy(hamming = 17))))
        assertEquals(listOf("gate.bigChange out of bounds 16–32"), problems(base.copy(gate = base.gate.copy(bigChange = 33))))
        assertEquals(listOf("gate.ringCompare out of bounds last|all"), problems(base.copy(gate = base.gate.copy(ringCompare = "x"))))
    }

    @Test fun refusesTooBig() {
        val raw = PlanFixtures.raw()
        assertEquals(listOf("plan is 8193 bytes (max 8192)"), PlanValidator.problems(raw, PlanFixtures.plan(), 8193))
        assertEquals(emptyList(), PlanValidator.problems(raw, PlanFixtures.plan(), 8192))
    }

    @Test fun refusesSensingKey() {
        for (block in listOf("sampleRateHz", "sensorConfig", "imuGyroHz", "tickMs", "fusionCadenceMs", "evaluateEveryMs", "detectorParams", "classifierCfg")) {
            val p = rawProblems { it.at("config")[block] = JsonObject(mapOf("hz" to JsonPrimitive(10))) }
            assertTrue("hz: sensing and detection are not plan parameters" in p, "$block $p")
        }
        val nested = rawProblems { it.at("config", "glance")["tickMs"] = JsonPrimitive(5) }
        assertTrue("tickMs: sensing and detection are not plan parameters" in nested, nested.toString())
    }

    @Test fun refusesNaN() {
        val raw = PlanFixtures.mutate { it.at("config", "glance")["onsetRadS"] = JsonPrimitive(Double.NaN) }
        assertTrue("config.glance.onsetRadS is not a finite number" in PlanValidator.problems(raw, null, 100))
        val inf = PlanFixtures.mutate { it.at("config", "phoneFusion")["fastKmh"] = JsonPrimitive(Double.POSITIVE_INFINITY) }
        assertTrue("config.phoneFusion.fastKmh is not a finite number" in PlanValidator.problems(inf, null, 100))
        val nul = PlanFixtures.mutate { it.at("config", "policy", "REST")["capPerMin"] = JsonNull }
        assertEquals(emptyList(), PlanValidator.problems(nul, null, 100))
    }

    @Test fun utf8BoundaryMultibyteName() {
        fun planWithBytes(target: Int): CapturePlan {
            // pad with ASCII, then end the name on a 2-byte character so that chars < bytes
            val empty = PlanFixtures.plan(name = "é")
            val bytes0 = PlanJson.encode(empty).toByteArray().size   // contains one 2-byte char
            return PlanFixtures.plan(name = "a".repeat(target - bytes0) + "é")
        }
        val ok = planWithBytes(8_192)
        val okText = PlanJson.encode(ok)
        assertEquals(8_192, okText.toByteArray().size)
        assertTrue(okText.length < 8_192)   // chars alone would hide the overflow
        assertEquals(emptyList(), PlanValidator.check(okText).first)
        val big = planWithBytes(8_193)
        val bigText = PlanJson.encode(big)
        assertEquals(8_193, bigText.toByteArray().size)
        assertEquals(listOf("plan is 8193 bytes (max 8192)"), PlanValidator.check(bigText).first)
    }
}
