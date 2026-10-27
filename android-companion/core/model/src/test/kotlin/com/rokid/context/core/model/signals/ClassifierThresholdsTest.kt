package com.rokid.context.core.model.signals

import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.normalized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.9.7 A: classifier thresholds are editable inside safe ranges, and the defaults are exactly the constants the classifier shipped with. */
class ClassifierThresholdsTest {
    @Test fun defaultsAreTheShippedConstants() {
        val d = ClassifierThresholds.DEFAULT
        assertEquals(0.6f, d.headMovingRadS)
        assertEquals(0.5f, d.walkVariance); assertEquals(1.5, d.walkMinHz); assertEquals(2.5, d.walkMaxHz)
        assertEquals(0.25f, d.relaxedVariance); assertEquals(1.2, d.relaxedMinHz); assertEquals(2.8, d.relaxedMaxHz); assertEquals(2_000L, d.relaxedHoldMs)
        assertEquals(0.25f, d.heurAccVar); assertEquals(1.2f, d.heurMinHz); assertEquals(2.8f, d.heurMaxHz); assertEquals(0.3f, d.heurAboveOnFrac)
    }

    @Test fun noOverridesIsTheDefaultAndHasTheDefaultHash() {
        assertEquals(ClassifierThresholds.DEFAULT, ClassifierThresholds.from(emptyMap()))
        assertEquals("default", ClassifierThresholds.hash(emptyMap()))
        assertEquals(emptyMap(), ClassifierThresholds.clean(mapOf("walkVariance" to 0.5)), "a value equal to the default is not an override")
        assertEquals("default", ClassifierThresholds.hash(mapOf("walkVariance" to 0.5)))
    }

    @Test fun valuesAreClampedIntoTheirRangeAndUnknownKeysDropped() {
        val c = ClassifierThresholds.clean(mapOf("headMovingRadS" to 9.0, "walkVariance" to -3.0, "relaxedHoldMs" to 10.0, "bogus" to 1.0, "walkMinHz" to Double.NaN))
        assertEquals(mapOf("headMovingRadS" to 1.2, "walkVariance" to 0.2, "relaxedHoldMs" to 1000.0), c)
        assertEquals(1.2f, ClassifierThresholds.from(c).headMovingRadS)
    }

    @Test fun anEditChangesTheValueAndTheHashAndTheHashIsStable() {
        val a = mapOf("walkVariance" to 0.4)
        assertEquals(0.4f, ClassifierThresholds.from(a).walkVariance)
        val h = ClassifierThresholds.hash(a)
        assertEquals(12, h.length)
        assertEquals(h, ClassifierThresholds.hash(a))
        assertTrue(h != ClassifierThresholds.hash(mapOf("walkVariance" to 0.41)))
        assertEquals(ClassifierThresholds.hash(mapOf("a" to 1.0) + a), h, "an unknown key does not move the hash")
    }

    @Test fun aMinFrequencyNeverReachesItsMax() {
        val c = ClassifierThresholds.clean(mapOf("walkMinHz" to 2.0, "walkMaxHz" to 2.0))
        val t = ClassifierThresholds.from(c)
        assertTrue(t.walkMinHz < t.walkMaxHz)
    }

    @Test fun settingsCarryItAdditivelyAndNormalizeCleansIt() {
        assertEquals(emptyMap(), HudSettings().classifier)
        assertEquals(emptyMap(), assertIs<Decoded.Ok<HudSettings>>(Wire.decode<HudSettings>("""{"v":1}""")).value.classifier, "an old phone sends none")
        assertEquals(mapOf("headMovingRadS" to 1.2), HudSettings(classifier = mapOf("headMovingRadS" to 50.0, "zz" to 1.0)).normalized().classifier)
        assertEquals(HudSettings().normalized(), HudSettings(classifier = mapOf("walkVariance" to 0.5)).normalized())
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test fun theMotionHintEchoesTheHashAndAnOldOneHasNone() {
        assertNull(MotionHint(atMs = 1).classifierHash)
        assertNull(assertIs<Decoded.Ok<MotionHint>>(Wire.decode<MotionHint>("""{"v":1,"atMs":1}""")).value.classifierHash)
    }

    @Test fun whatAClassifierHoldsRoundTripsToItsOverrides() {
        assertEquals(emptyMap(), ClassifierThresholds.DEFAULT.toOverrides())
        val o = mapOf("headMovingRadS" to 0.7, "walkMaxHz" to 3.0, "relaxedHoldMs" to 3000.0, "heurAboveOnFrac" to 0.45)
        assertEquals(o, ClassifierThresholds.from(o).toOverrides())
        assertEquals(ClassifierThresholds.hash(o), ClassifierThresholds.hash(ClassifierThresholds.from(o).toOverrides()))
    }
}
