package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AtomicJpegWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun dirIsFsyncedAfterRename() {
        val dir = File(tmp.root, "rokid_inbox")
        val target = File(dir, "a.jpg")
        val synced = mutableListOf<Pair<File, Boolean>>()
        AtomicJpegWriter.write(target, byteArrayOf(1, 2), lastModifiedMs = null, fsyncDir = { synced += it to target.isFile; false })
        assertEquals("once, with the inbox, after the rename", listOf(dir to true), synced)
        assertArrayEquals("a failed dir fsync still counts as written", byteArrayOf(1, 2), target.readBytes())
    }
}
