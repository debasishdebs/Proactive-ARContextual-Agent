package com.rokid.context.core.model

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDurabilityTest {
    @Test fun fsyncDirReturnsTrueForARealDirectory() {
        val dir = Files.createTempDirectory("fsync").toFile()
        try { assertTrue(FileDurability.fsyncDir(dir)) } finally { dir.deleteRecursively() }
    }

    @Test fun fsyncDirReturnsFalseForAMissingDirectory() {
        val dir = Files.createTempDirectory("fsync").toFile()
        dir.delete()
        assertFalse(FileDurability.fsyncDir(File(dir, "missing")))
    }
}
