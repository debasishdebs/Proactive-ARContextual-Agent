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
    private val registeredNames = (DeletionGraph.TABLES + DeletionGraph.PREFS + DeletionGraph.GLASSES_PREFS + DeletionGraph.GLASSES_FILES + DeletionGraph.DIRS + DeletionGraph.CACHE_DIRS + DeletionGraph.EXTERNAL_DIRS).map { it.name }.toSet()

    @Test fun deleteAllClearsExactlyTheRegisteredPrefsAndFolders() {
        assertEquals(DeletionGraph.wipedPrefs.toSet(), MemoryWipe.WIPED_PREFS.toSet())
        assertEquals(DeletionGraph.wipedDirs.toSet(), FencedFiles.DELETE_ALL_DIRS.toSet())
        assertEquals(DeletionGraph.wipedExternalDirs.toSet(), setOf(FencedFiles.STAGING_DIR))
        assertTrue(CollectSession.PREFS in MemoryWipe.WIPED_PREFS)
    }

    /** The opt-in street map (v1.0.1): its switch and consent file and its tile cache folder (under cacheDir, not filesDir) are wiped and registered. */
    @Test fun theOnlineMapPrefsAndTileCacheAreWipedAndRegistered() {
        assertTrue("online_map" in MemoryWipe.WIPED_PREFS); assertTrue("online_map" in DeletionGraph.wipedPrefs)
        assertEquals(setOf(MemoryWipe.ONLINE_TILES_DIR), DeletionGraph.wipedCacheDirs.toSet())
        assertEquals("online_tiles", MemoryWipe.ONLINE_TILES_DIR)
    }

    /** Any string literal of the form "canvas_*" / "mark_*" assigned to a PREFS or DIR constant in a main source is an on-disk artefact and needs a decision. */
    @Test fun noUnregisteredPrefsOrFolderConstantInAnyMainSource() {
        val decl = Regex("""const val (\w*(?:PREFS|DIR)\w*)\s*=\s*"((?:canvas|mark|signal)_\w+)"""")
        val found = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.extension == "kt" && "/src/main/" in it.path }
            .flatMap { f -> decl.findAll(f.readText()).map { it.groupValues[2] to f.name } }.toList()
        assertTrue("scan broken", found.size >= 4)
        val missing = found.filter { it.first !in registeredNames }
        assertTrue("on-disk artefacts with no deletion decision (add to DeletionGraph and Delete all): $missing", missing.isEmpty())
    }

    /**
     * The wide net (the const-name scan above is easy to dodge): ANY string literal that starts with canvas / mark / imu / trace and sits on a line that builds a File, opens a
     * preferences file or names a folder, in ANY main source of the app, the services, the glasses and the cores, must be a registered artefact.
     */
    @Test fun noFileOrPrefsLiteralInAnyMainSourceEscapesTheRegistry() {
        val lit = Regex(""""((?:canvas|mark|imu|trace|traces|signal|online)[a-z_.]*)"""")
        val ctx = Regex("""File\(|getSharedPreferences|PREFS|DIR|Dir\b|filesDir|cacheDir|\.xml""")
        val found = HashMap<String, MutableSet<String>>()
        root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.extension == "kt" && "/src/main/" in it.path }
            .forEach { f -> f.readLines().filter { ctx.containsMatchIn(it) }.forEach { l -> lit.findAll(l).forEach { found.getOrPut(it.groupValues[1]) { HashSet() } += f.name } } }
        assertTrue("scan broken: ${found.keys}", found.size >= 10)
        val missing = found.filterKeys { it !in registeredNames }
        assertTrue("on-disk artefacts with no deletion decision (add to DeletionGraph and make Delete all reach them): $missing", missing.isEmpty())
    }

    @Test fun everyBackupExcludedCanvasPathIsRegistered() {
        val rules = File(root, "phone/src/main/res/xml/backup_rules.xml").readText()
        val paths = Regex("""path="((?:canvas|mark|signal|online)_\w+|deletion_epoch)(?:\.xml)?"""").findAll(rules).map { it.groupValues[1] }.toSet()
        assertTrue(paths.size >= 6)
        val missing = paths.filter { it !in registeredNames }
        assertTrue("backup-excluded but undecided: $missing", missing.isEmpty())
    }
}
