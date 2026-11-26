package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** V1 offline photo captions: the `offlineCaption` Developer flag is OFF by default, lives in `pc_dev`, and a prefs read that fails counts as OFF. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OfflineCaptionFlagTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun offByDefaultAndSwitchable() {
        assertEquals("offlineCaption", DeveloperFlags.OFFLINE_CAPTION)
        ctx.getSharedPreferences(DeveloperFlags.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(DeveloperFlags.offlineCaption(ctx))
        DeveloperFlags.setOfflineCaption(ctx, true)
        assertTrue(DeveloperFlags.offlineCaption(ctx))
        DeveloperFlags.setOfflineCaption(ctx, false)
        assertFalse(DeveloperFlags.offlineCaption(ctx))
    }

    @Test fun theSourceDefaultsToFalseAndFallsBackToFalse() {
        val src = listOf("src/main/java/com/rokid/context/orchestration/forget/ForgetShadow.kt", "services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetShadow.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(Regex("""fun offlineCaption\(.*getBoolean\(OFFLINE_CAPTION, false\).*getOrDefault\(false\)""").containsMatchIn(src))
    }
}
