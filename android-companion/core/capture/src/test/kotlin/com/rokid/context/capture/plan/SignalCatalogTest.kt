package com.rokid.context.capture.plan

import com.rokid.context.glasses.capture.motion.model.SecondFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalCatalogMatchesSecondFeaturesTest {
    @Test fun featureIndexFollowsToArrayOrder() {
        val f = SecondFeatures(gyroMax = 1f, gyroMean = 2f, gyroRms = 3f, aboveOnFrac = 4f, accVar = 5f, stepHz = 6f, rotRad = 7f)
        val expected = mapOf("gyroMax" to 1f, "gyroMean" to 2f, "gyroRms" to 3f, "aboveOnFrac" to 4f, "accVar" to 5f, "stepHz" to 6f, "rotRad" to 7f)
        assertEquals(expected.keys, SignalCatalog.featureNames.toSet())
        for ((name, v) in expected) assertEquals(v, f.toArray()[SignalCatalog.featureIndex(name)!!], name)
        assertEquals(listOf("gyroMax", "gyroMean", "gyroRms", "aboveOnFrac", "accVar", "stepHz", "rotRad"), SignalCatalog.featureNames)
    }

    @Test fun headSpeedIsAnAliasOfGyroMax() {
        assertEquals("gyroMax", SignalCatalog.canonical("headSpeed"))
        assertEquals("gyroMax", SignalCatalog.canonical("gyroMax"))
        assertNull(SignalCatalog.canonical("nope"))
        assertEquals(SignalCatalog.featureIndex("gyroMax"), SignalCatalog.featureIndex("headSpeed"))
    }

    @Test fun catalogueShape() {
        assertEquals(1, SignalCatalog.SCHEMA)
        val ctx = SignalCatalog.byName("ctx")!!
        assertEquals(SignalCatalog.Type.ENUM, ctx.type)
        assertTrue("REST" in ctx.enumValues && "WALKING" in ctx.enumValues && "UNKNOWN" in ctx.enumValues)
        assertEquals("UNKNOWN", ctx.default); assertEquals(3_000L, SignalCatalog.byName("gyroMax")!!.staleAfterMs)
        assertEquals(0.0, SignalCatalog.byName("gyroMax")!!.default)
        // only signals the glasses compute today (MotionModel + ContextGate); no per-glance or per-keep signals yet
        assertNull(SignalCatalog.byName("boutMs")); assertNull(SignalCatalog.byName("ctxConf"))
        assertTrue(SignalCatalog.toJson().toString().contains("\"sigSchema\":1"))
    }
}
