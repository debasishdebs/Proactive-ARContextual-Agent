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
        // Markers that say "already done in the DB": restored without the DB they would skip re-seeding/re-import.
        "sharedpref:ai_seed.xml", "sharedpref:legacy_import.xml",
        "database:context.db", "database:context.db-wal", "database:context.db-shm",
    )

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

    @Test fun legacyFullBackupExcludesKeysAndDbOnly() {
        val root = parse("src/main/res/xml/backup_rules.xml")
        assertEquals(excluded, excludes(root))
        assertEquals("no <include>: everything else stays backed up", 0, includes(root))
    }

    @Test fun cloudBackupAndDeviceTransferExcludeKeysAndDbOnly() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")
        for (tag in listOf("cloud-backup", "device-transfer")) {
            val section = root.getElementsByTagName(tag).item(0) as Element?
            assertTrue("missing <$tag>", section != null)
            assertEquals(tag, excluded, excludes(section!!))
            assertEquals("$tag: no <include>", 0, includes(section))
        }
    }
}
