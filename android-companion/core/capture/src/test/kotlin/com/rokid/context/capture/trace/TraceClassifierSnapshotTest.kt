package com.rokid.context.capture.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v0.9.7 A: the trace start snapshot records the classifier threshold overrides, additively (TRACE_SCHEMA 1, an older trace = defaults). */
class TraceClassifierSnapshotTest {
    private val base = TraceFixtures.manifest()

    @Test fun aDefaultTraceKeepsItsExactBytesAndHasNoClassifierKey() {
        assertEquals(TraceFixtures.goldenManifest.replace("\$GOLDEN_TRACE_SHA", TraceFixtures.GOLDEN_TRACE_SHA), base.encode())
        assertFalse("classifier" in base.encode())
    }

    @Test fun anEditedClassifierRoundTripsAndAnOlderManifestDecodesToDefaults() {
        val m = base.copy(snapshot = base.snapshot.copy(classifier = mapOf("walkVariance" to 0.4, "relaxedHoldMs" to 3000.0)))
        val text = m.encode()
        assertTrue(""""classifier":{"relaxedHoldMs":3000000,"walkVariance":400}""" in text)
        assertEquals(m.snapshot.classifier, Manifest.decodeStrict(text).getOrThrow().snapshot.classifier)
        assertEquals(emptyMap(), Manifest.decodeStrict(base.encode()).getOrThrow().snapshot.classifier)
    }

    @Test fun aNonCanonicalClassifierIsRefused() {
        assertFailsWith<IllegalArgumentException> { base.snapshot.copy(classifier = mapOf("walkVariance" to 99.0)) }
        assertFailsWith<IllegalArgumentException> { base.snapshot.copy(classifier = mapOf("bogus" to 1.0)) }
    }

    @Test fun theSimulatorRunsTheClassifierTheGlassesRan() {
        val edited = TraceFixtures.snapshot.copy(classifier = mapOf("walkVariance" to 0.4))
        val c = com.rokid.context.capture.replay.CaptureSimulator.classifierFor(edited, edited.glance) { 0L }
        assertEquals(0.4f, c.thresholds.walkVariance)
        assertEquals(com.rokid.context.core.model.signals.ClassifierThresholds.DEFAULT, com.rokid.context.capture.replay.CaptureSimulator.classifierFor(TraceFixtures.snapshot, TraceFixtures.glance) { 0L }.thresholds)
    }
}
