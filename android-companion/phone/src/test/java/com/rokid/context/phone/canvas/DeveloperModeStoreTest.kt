package com.rokid.context.phone.canvas

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DeveloperModeStoreTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun defaultOff() {
        app.getSharedPreferences("pc_dev", 0).edit().clear().commit()
        assertFalse(DeveloperModeStore(app).enabled.value)
    }

    @Test fun persists() {
        app.getSharedPreferences("pc_dev", 0).edit().clear().commit()
        val a = DeveloperModeStore(app)
        a.set(true)
        assertTrue(a.enabled.value)
        assertTrue(DeveloperModeStore(app).enabled.value)
        assertTrue(app.getSharedPreferences("pc_dev", 0).getBoolean("developer_mode", false))
        a.set(false)
        assertFalse(DeveloperModeStore(app).enabled.value)
    }
}
