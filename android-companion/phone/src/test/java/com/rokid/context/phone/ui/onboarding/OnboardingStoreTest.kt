package com.rokid.context.phone.ui.onboarding

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingStoreTest {
    @Test fun completionPersistsAcrossInstances() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(OnboardingStore(ctx).isComplete())
        OnboardingStore(ctx).markComplete()
        assertTrue(OnboardingStore(ctx).isComplete())
    }
}
