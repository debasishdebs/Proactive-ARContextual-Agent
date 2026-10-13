package com.rokid.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * After a restore, EncryptedSharedPreferences come back without their Keystore master key, so the encrypted key
 * files and the Room DB (whose SQLCipher passphrase lives in one of them) must never be backed up or transferred.
 */
class BackupRulesTest {
    private val excluded = setOf(
        "sharedpref:endpoint_keys.xml", "sharedpref:db_key.xml",
        // T21 fix round 1 (I3): the legacy key file, which an old build may have written in plaintext.
        "sharedpref:app_config_secret.xml",
        // Markers that say "already done in the DB": restored without the DB they would skip re-seeding/re-import.
        "sharedpref:ai_seed.xml", "sharedpref:legacy_import.xml",
        "database:context.db", "database:context.db-wal", "database:context.db-shm",
    ) + memoryCopies

    /**
     * Final-review B1: copies of memory/user text outside the DB — frame JPEGs, the queue log (fact and question text
     * in payloads), the legacy logs and the exports — are neither backed up nor transferred.
     */
    private val memoryCopies: Set<String> get() =
        com.rokid.context.data.frames.RokidFrameInbox.FRAME_DIRS.map { "file:$it" }.toSet() +
            com.rokid.context.memory.legacy.LegacyImporter.LEGACY_FILES.map { "file:$it" } +
            "file:queue_runtime.log" + "root:cache/exports"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun excludes(section: Element): Set<String> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }.toSet()
    }

    private fun includes(section: Element) = section.getElementsByTagName("include").length

    @Test fun manifestPointsAtBothRuleFiles() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("true", app.getAttributeNS(ns, "allowBackup"))
    }

    @Test fun legacyFullBackupExcludesKeysDbAndMemoryCopiesOnly() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals(excluded, excludes(root))
        assertEquals("no <include>: everything else stays backed up", 0, includes(root))
    }

    @Test fun cloudBackupAndDeviceTransferExcludeKeysDbAndMemoryCopiesOnly() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = root.getElementsByTagName(tag).item(0) as Element?
            assertTrue("missing <$tag>", section != null)
            assertEquals(tag, excluded, excludes(section!!))
            assertEquals("$tag: no <include>", 0, includes(section))
        }
    }

    /** The queue log's name is private to QueueRuntimeStore: pin it so a rename can't silently drop the exclusion. */
    @Test fun theQueueLogNameMatchesTheStore() {
        val src = File("../services/orchestration/src/main/java/com/rokid/context/data/runtime/QueueRuntimeStore.kt").readText()
        assertTrue(src.contains("FILE_NAME = \"queue_runtime.log\""))
    }

    /** Every prefs file opened through EncryptedSharedPreferences in any module is a secret file and must be excluded. */
    @Test fun everySecretPrefsFileIsExcluded() {
        val roots = listOf("src/main", "../services/ai/src/main", "../services/memory/src/main", "../services/orchestration/src/main", "../glasses/src/main")
        val names = roots.map(::File).filter { it.isDirectory }.flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") } }
            .flatMap { f ->
                val src = f.readText()
                src.split("EncryptedSharedPreferences.create(").drop(1).map { call ->
                    val arg = call.split(",")[1].trim()
                    if (arg.startsWith("\"")) arg.trim('"')
                    else Regex("""const val $arg = "([^"]+)"""").find(src)?.groupValues?.get(1) ?: error("${f.name}: can't resolve $arg")
                }
            }.toSet()
        assertEquals(setOf("endpoint_keys", "db_key", "app_config_secret"), names)
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        for (section in sections) for (n in names) assertTrue("${section.tagName}: $n.xml", "sharedpref:$n.xml" in excludes(section))
    }
}
