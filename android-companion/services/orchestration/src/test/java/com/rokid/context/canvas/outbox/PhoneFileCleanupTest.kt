package com.rokid.context.canvas.outbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The production file port: real deletion, confirmed, and never outside the app's own directories. */
class PhoneFileCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun files() = tmp.newFolder("files")
    private fun port(root: File) = PhoneFileCleanup(listOf(root))

    @Test fun deletesAFileInsideARootAndConfirmsItIsGone() = runBlocking<Unit> {
        val root = files(); val f = File(root, "inbox/a.jpg").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertEquals(CleanupResult.DONE, port(root).deleteConfirmed(f.absolutePath)); assertFalse(f.exists())
    }

    @Test fun aMissingFileIsDone() = runBlocking<Unit> { val root = files(); assertEquals(CleanupResult.DONE, port(root).deleteConfirmed(File(root, "gone.jpg").absolutePath)) }

    @Test fun aPathOutsideTheRootsIsRefusedAndNothingIsDeleted() = runBlocking<Unit> {
        val root = files(); val other = tmp.newFile("elsewhere.jpg")
        assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed(other.absolutePath)); assertTrue(other.exists())
        val sneaky = File(root, "../elsewhere.jpg").path
        assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed(sneaky)); assertTrue(other.exists())
        assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed("")); assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed(root.absolutePath)); assertTrue(root.exists())
    }

    @Test fun aSiblingDirectorySharingTheRootsNamePrefixIsOutside() = runBlocking<Unit> {
        val root = files(); val sibling = tmp.newFolder("files-evil"); val f = File(sibling, "a.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed(f.absolutePath)); assertTrue(f.exists())
    }

    @Test fun aDirectoryIsNeverDeleted() = runBlocking<Unit> {
        val root = files(); val d = File(root, "inbox").apply { mkdirs() }; File(d, "x").writeBytes(byteArrayOf(1))
        assertEquals(CleanupResult.FAILED, port(root).deleteConfirmed(d.absolutePath)); assertTrue(File(d, "x").exists())
    }

    @Test fun aRelativePathIsRefusedEvenWhenItResolvesInsideARoot() = runBlocking<Unit> {
        val cwd = File(System.getProperty("user.dir")!!); val f = File(cwd, "outbox-relative-path-test.tmp").apply { writeBytes(byteArrayOf(1)) }
        try { assertEquals(CleanupResult.FAILED, PhoneFileCleanup(listOf(cwd)).deleteConfirmed(f.name)); assertTrue(f.exists()) } finally { f.delete() }
    }
}
