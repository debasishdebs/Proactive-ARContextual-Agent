package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.orchestration.forget.DeveloperFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** V2b: the Developer flag and the Place Guide settings of the offline tier: defaults and ranges. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineTierSettingsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun fresh(): PlaceGuideSettings { ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit(); return PlaceGuideSettings.of(ctx) }

    @Test fun theDeveloperFlagIsOnByDefaultAndSwitchable() {
        assertEquals("offlineTier", DeveloperFlags.OFFLINE_TIER)
        ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(DeveloperFlags.offlineTier(ctx)) // ON by default since 27 Nov (layered confirmation, D13); the model download still gates it
        DeveloperFlags.setOfflineTier(ctx, false); assertFalse(DeveloperFlags.offlineTier(ctx))
        DeveloperFlags.setOfflineTier(ctx, true); assertTrue(DeveloperFlags.offlineTier(ctx))
    }

    @Test fun theSourceDefaultsToTrueAndAFailedReadIsOff() {
        val src = listOf("src/main/java/com/rokid/context/orchestration/forget/ForgetShadow.kt", "services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetShadow.kt").map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(Regex("""fun offlineTier\(.*getBoolean\(OFFLINE_TIER, true\).*getOrDefault\(false\)""").containsMatchIn(src))
    }

    @Test fun theUserSwitchIsOnAndTheDefaultsAreTheOwnersNumbers() {
        val s = fresh()
        assertTrue(s.offlineGuess); assertEquals(0.55f, s.maybeCutoff); assertEquals(150, s.candidateRadiusM)
    }

    @Test fun valuesAreStoredAndClampedToTheirRanges() {
        val s = fresh()
        s.offlineGuess = true; assertTrue(s.offlineGuess)
        s.maybeCutoff = 0.70f; assertEquals(0.70f, s.maybeCutoff); s.maybeCutoff = 0.1f; assertEquals(0.30f, s.maybeCutoff); s.maybeCutoff = 1.5f; assertEquals(0.90f, s.maybeCutoff)
        s.maybeCutoff = Float.NaN; assertEquals("NaN is ignored", 0.90f, s.maybeCutoff)
        s.candidateRadiusM = 300; assertEquals(300, s.candidateRadiusM); s.candidateRadiusM = 5; assertEquals(50, s.candidateRadiusM); s.candidateRadiusM = 9_000; assertEquals(500, s.candidateRadiusM)
    }

    @Test fun aStoredValueOutOfRangeReadsAsTheDefault() {
        val s = fresh()
        ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE).edit().putFloat("maybeCutoff", 0.05f).putInt("candidateRadiusM", 4_000).commit()
        assertEquals(0.55f, s.maybeCutoff); assertEquals(150, s.candidateRadiusM)
    }

    @Test fun maybeIsAHistoryKindStoredInTheExistingKindColumn() {
        assertTrue(HistoryKind.entries.any { it.name == "MAYBE" })
        assertEquals(PlaceConfidence.MAYBE, PlaceConfidence.entries.single { it.wire == "maybe" })
    }
}
