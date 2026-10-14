package com.rokid.context.data.frames

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DirSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theTestOverrideReplacesTheDefault() {
        DirSync.testOverride = DirSync { true }
        try { assertTrue(DirSync.Default.sync(File(tmp.root, "nope"))) } finally { DirSync.testOverride = null }
    }

    @Test fun defaultFailsForAMissingDirectory() {
        assertFalse(DirSync.Default.sync(File(tmp.root, "nope")))
    }
}
