package com.rokid.context.data.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppConfigSecretTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun clearingTheLegacySecretRemovesAPlaintextFallbackFileToo() {
        ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit().putString("api_key", "sk-legacy-1").commit()
        AppConfigStore.clearLegacySecret(ctx)
        assertEquals("", AppConfigStore.getWithLegacySecret(ctx).apiKey)
        assertFalse(File(ctx.applicationInfo.dataDir, "shared_prefs/app_config_secret.xml").exists())
    }

    /** Hard requirement (no plaintext key anywhere): a key an old build left in plaintext is still read once, for the seed. */
    @Test fun aPlaintextKeyAnOldBuildLeftIsReadForTheSeedUntilCleared() {
        ctx.getSharedPreferences("app_config_secret", Context.MODE_PRIVATE).edit().putString("api_key", " sk-legacy-2 ").commit()
        assertEquals("sk-legacy-2", AppConfigStore.getWithLegacySecret(ctx).apiKey)
        assertEquals("", AppConfigStore.getWithoutSecret(ctx).apiKey)
        AppConfigStore.clearLegacySecret(ctx)
        assertEquals("", AppConfigStore.getWithLegacySecret(ctx).apiKey)
    }
}
