package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StorageUsageTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun percentScalesAndClamps() {
        assertEquals(50, StorageUsage.percent(50L * 1024 * 1024))
        assertEquals(0, StorageUsage.percent(-1))
        assertEquals(250, StorageUsage.percent(250L * 1024 * 1024))
        assertEquals(0, StorageUsage.percent(10, quotaBytes = 0))
    }

    @Test fun usedBytesCountsFrameFolders() = runBlocking {
        File(ctx.filesDir, "rokid_archive").apply { mkdirs() }.resolve("a.jpg").writeBytes(ByteArray(1024 * 1024))
        File(ctx.filesDir, "rokid_inbox").apply { mkdirs() }.resolve("b.jpg").writeBytes(ByteArray(512 * 1024))
        File(ctx.filesDir, "unrelated.bin").writeBytes(ByteArray(4096))
        assertEquals(1_572_864L, StorageUsage.usedBytes(ctx))
    }
}
