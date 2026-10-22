package com.rokid.context.canvas.plan

import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.memory.forget.DeletionGraph
import com.rokid.context.orchestration.MemoryWipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The files and prefs half of the inventory: every canvas/mark preference file and folder any main source or backup rule names must be in [DeletionGraph], and what
 * Delete all really clears must equal the registry. A later task that adds `"canvas_x"` prefs or a folder without deciding its fate fails here.
 */
class DeletionInventoryFilesTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val registeredNames = (DeletionGraph.TABLES + DeletionGraph.PREFS + DeletionGraph.GLASSES_PREFS + DeletionGraph.DIRS).map { it.name }.toSet()

    @Test fun deleteAllClearsExactlyTheRegisteredPrefsAndFolders() {
        assertEquals(DeletionGraph.wipedPrefs.toSet(), MemoryWipe.WIPED_PREFS.toSet())
        assertEquals(DeletionGraph.wipedDirs.toSet(), FencedFiles.DELETE_ALL_DIRS.toSet())
        assertTrue(CollectSession.PREFS in MemoryWipe.WIPED_PREFS)
    }

    /** Any string literal of the form "canvas_*" / "mark_*" assigned to a PREFS or DIR constant in a main source is an on-disk artefact and needs a decision. */
    @Test fun noUnregisteredPrefsOrFolderConstantInAnyMainSource() {
        val decl = Regex("""const val (\w*(?:PREFS|DIR)\w*)\s*=\s*"((?:canvas|mark)_\w+)"""")
        val found = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.extension == "kt" && "/src/main/" in it.path }
            .flatMap { f -> decl.findAll(f.readText()).map { it.groupValues[2] to f.name } }.toList()
        assertTrue("scan broken", found.size >= 4)
        val missing = found.filter { it.first !in registeredNames }
        assertTrue("on-disk artefacts with no deletion decision (add to DeletionGraph and Delete all): $missing", missing.isEmpty())
    }

    @Test fun everyBackupExcludedCanvasPathIsRegistered() {
        val rules = File(root, "phone/src/main/res/xml/backup_rules.xml").readText()
        val paths = Regex("""path="((?:canvas|mark)_\w+|deletion_epoch)(?:\.xml)?"""").findAll(rules).map { it.groupValues[1] }.toSet()
        assertTrue(paths.size >= 6)
        val missing = paths.filter { it !in registeredNames }
        assertTrue("backup-excluded but undecided: $missing", missing.isEmpty())
    }
}
