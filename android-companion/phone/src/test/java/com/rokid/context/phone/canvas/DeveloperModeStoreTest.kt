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

    @Test fun sessionLifetimeIsOffByDefaultOnlyOneOfTheChoicesAndNeedsDeveloperMode() {
        app.getSharedPreferences("pc_dev", 0).edit().clear().commit()
        val s = DeveloperModeStore(app)
        assertTrue(s.sessionHours.value == 0 && s.sessionLifetimeMs() == 0L)
        s.setSessionHours(72); s.set(false)
        assertTrue("Developer mode off: no longer session", s.sessionLifetimeMs() == 0L)
        s.set(true)
        assertTrue(s.sessionLifetimeMs() == 72 * 3_600_000L && DeveloperModeStore(app).sessionHours.value == 72)
        s.setSessionHours(1000); assertTrue("not one of the choices: Off", s.sessionHours.value == 0)
        s.set(false)
    }

    @Test fun theVaultKeepsDigestsOnlyAndClearsWhenTheSettingGoesOff() {
        app.getSharedPreferences("pc_dev", 0).edit().clear().commit()
        val s = DeveloperModeStore(app)
        s.vault.write(listOf(com.rokid.context.phone.canvas.server.CanvasAuth.StoredSession("a".repeat(64), 5L, 100L)))
        assertTrue(DeveloperModeStore(app).vault.read() == listOf(com.rokid.context.phone.canvas.server.CanvasAuth.StoredSession("a".repeat(64), 5L, 100L)))
        s.setSessionHours(0)
        assertTrue(s.vault.read().isEmpty())
    }
}
