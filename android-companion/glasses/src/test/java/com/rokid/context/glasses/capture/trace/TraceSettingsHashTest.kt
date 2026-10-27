package com.rokid.context.glasses.capture.trace

import com.rokid.context.core.model.HudSettings
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** v0.9.7 A: the trace settings hash records edited classifier thresholds, and with none it is byte-identical to before. */
class TraceSettingsHashTest {
    private fun old(s: HudSettings): String {
        val text = "${s.captureMode}|${s.quickLookMs}|${s.cameraWake}|${s.workMinutes}|${s.restMinutes}|${s.batteryTargetHours}|${s.pauseWhenNotWorn}|${s.pauseWhenPhoneInUse}|${s.workRestEnabled}"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }

    @Test fun defaultsKeepTheExactOldHash() {
        val s = HudSettings()
        assertEquals(old(s), TraceSettingsHash.of(s))
        assertEquals(old(s), TraceSettingsHash.of(s.copy(classifier = mapOf("walkVariance" to 0.5))), "an override equal to the default is no override")
    }

    @Test fun anEditChangesItAndDifferentEditsDiffer() {
        val a = TraceSettingsHash.of(HudSettings(classifier = mapOf("walkVariance" to 0.4)))
        assertNotEquals(old(HudSettings()), a)
        assertNotEquals(a, TraceSettingsHash.of(HudSettings(classifier = mapOf("walkVariance" to 0.45))))
    }
}
