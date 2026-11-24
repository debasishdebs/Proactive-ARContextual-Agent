package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NetworkOnlineTest {
    @Test fun noDefaultNetworkIsOffline() = assertFalse(hasInternet(null))

    @Test fun internetCapabilityIsOnline() {
        val c = ShadowNetworkCapabilities.newInstance(); org.robolectric.Shadows.shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        assertTrue(hasInternet(c))
    }

    @Test fun noInternetCapabilityIsOffline() = assertFalse(hasInternet(ShadowNetworkCapabilities.newInstance()))
}
