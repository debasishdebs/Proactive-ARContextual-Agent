package com.rokid.context.phone

import android.app.Application
import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AppIconTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun manifestUsesTheAdaptiveLauncherIcon() {
        assertEquals(R.mipmap.ic_launcher, ctx.applicationInfo.icon)
        assertTrue(ctx.getDrawable(R.mipmap.ic_launcher) is AdaptiveIconDrawable)
        assertTrue(ctx.getDrawable(R.mipmap.ic_launcher_round) is AdaptiveIconDrawable)
    }
}
