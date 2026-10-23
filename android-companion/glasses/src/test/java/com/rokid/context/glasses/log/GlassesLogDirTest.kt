package com.rokid.context.glasses.log

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review lgc3 #1: argument construction for the installer must not call `Context.getFilesDir()` (it checks/creates the directory = main-thread IO). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlassesLogDirTest {
    private val data = kotlin.io.path.createTempDirectory("gldata").toFile()

    private class Spy(base: Context, private val info: ApplicationInfo) : ContextWrapper(base) {
        val filesDirCalls = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun getFilesDir(): File { filesDirCalls += "getFilesDir"; return super.getFilesDir() }
        override fun getApplicationInfo(): ApplicationInfo = info
    }

    @Test fun theLogDirComesFromDataDirWithoutTouchingTheFilesDirGetterOrTheDisk() {
        val info = ApplicationInfo(ApplicationProvider.getApplicationContext<Context>().applicationInfo).also { it.dataDir = File(data, "app").path }
        val spy = Spy(ApplicationProvider.getApplicationContext(), info)
        val dir = GlassesLogging.logDir(spy)
        assertEquals(File(File(data, "app"), "files"), dir)
        assertTrue(spy.filesDirCalls.isEmpty(), "getFilesDir called: ${spy.filesDirCalls}")
        assertFalse(File(data, "app").exists(), "nothing was created")
        assertFalse(dir.exists())
    }
}
