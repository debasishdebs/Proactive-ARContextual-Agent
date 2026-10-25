package com.rokid.context.data.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/** B12: restored copies of the legacy key file are deleted, and the log line carries a count only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SecretBakCleanupTest {
    @get:org.junit.Rule val pcLogShadow = com.rokid.context.PcLogToShadowRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val sentinel = "SENTINEL-not-a-real-key-42"
    private val names = listOf("app_config_secret.xml.bak.1780057256", "app_config_secret.xml.bak.manual_reset_1780057830")

    @Before fun setUp() { ShadowLog.reset() }

    @Test fun cleanupNeverLogsContents() {
        names.forEach { File(ctx.filesDir, it).writeText("<map><string name=\"api_key\">$sentinel</string></map>") }
        assertEquals(2, SecretBakCleanup.run(ctx))
        val lines = ShadowLog.getLogs().filter { it.tag == SecretBakCleanup.TAG }
        assertEquals(1, lines.size)
        assertTrue(lines[0].msg, Regex("""^Deleted \d+ restored secret backup file\(s\)$""").matches(lines[0].msg))
        assertTrue(lines[0].msg.contains("2"))
        val all = ShadowLog.getLogs().joinToString("\n") { "${it.msg} ${it.throwable}" }
        assertFalse("contents logged", all.contains(sentinel))
        assertFalse("file name logged", names.any { all.contains(it) })
    }

    @Test fun cleanupIsIdempotentAndSilentWhenNothingIsThere() {
        File(ctx.filesDir, names[0]).writeText("x")
        assertEquals(1, SecretBakCleanup.run(ctx))
        ShadowLog.reset()
        assertEquals(0, SecretBakCleanup.run(ctx))
        assertTrue(ShadowLog.getLogs().none { it.tag == SecretBakCleanup.TAG })
    }

    @Test fun onlyBakCopiesOfTheSecretFileGo() {
        val keep = listOf("queue_runtime.log", "app_config.xml.bak.1", "rokid_inbox").map { File(ctx.filesDir, it).apply { writeText("x") } }
        File(ctx.filesDir, names[1]).writeText("x")
        assertEquals(1, SecretBakCleanup.run(ctx))
        assertTrue(keep.all { it.exists() })
        keep.forEach { it.delete() }
    }
}
