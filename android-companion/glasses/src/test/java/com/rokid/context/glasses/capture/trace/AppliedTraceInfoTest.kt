package com.rokid.context.glasses.capture.trace

import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.signals.ClassifierThresholds
import com.rokid.context.glasses.capture.session.BudgetTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** v0.9.7 A: the trace follows the classifier's APPLIED thresholds, not the settings cache that may be a tick ahead. */
class AppliedTraceInfoTest {
    private fun info(settings: HudSettings, applied: ClassifierThresholds) = appliedTraceInfo(settings, applied, 0, null, PlanMode.SETTINGS, BudgetTier.T0)

    @Test fun aSettingNotYetAppliedIsNeitherRecordedNorHashed() {
        val ahead = HudSettings(classifier = mapOf("walkVariance" to 0.4))   // the cache already holds it, the classifier still runs the defaults
        val i = info(ahead, ClassifierThresholds.DEFAULT)
        assertEquals(emptyMap(), i.classifier)
        assertEquals(info(HudSettings(), ClassifierThresholds.DEFAULT).settingsHash, i.settingsHash)
    }

    @Test fun applyingThemChangesTheInfoSoTheRecorderSplitsTheTrace() {
        val s = HudSettings(classifier = mapOf("walkVariance" to 0.4))
        val before = info(s, ClassifierThresholds.DEFAULT)
        val after = info(s, ClassifierThresholds.from(mapOf("walkVariance" to 0.4)))
        assertEquals(mapOf("walkVariance" to 0.4), after.classifier)
        assertNotEquals(before.settingsHash, after.settingsHash)
        assertEquals(TraceSettingsHash.of(s), after.settingsHash)
    }
}
