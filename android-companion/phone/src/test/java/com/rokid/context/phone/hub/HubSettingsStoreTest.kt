package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubSettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hss_test", 0)

    /** M12: quiet-hours minutes are range-checked at write and at read. */
    @Test fun outOfRangeQuietHoursAreDroppedAtWriteAndRead() {
        val store = HubSettingsStore(prefs) { "hud" }
        store.write(HudSettings(quietHours = QuietHours(22 * 60, 1_440)))
        assertNull(store.read().quietHours)
        prefs.edit().putString("hud_settings", Wire.encode(HudSettings(quietHours = QuietHours(-1, 60)))).commit()
        assertNull(store.read().quietHours)
        store.write(HudSettings(quietHours = QuietHours(0, 1_439)))
        assertEquals(QuietHours(0, 1_439), store.read().quietHours)
    }
}
