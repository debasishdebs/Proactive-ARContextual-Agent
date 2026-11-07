package com.rokid.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // On-device embeddings spec section 4: downloaded models (large, re-downloadable) are never backed up.
        "file:models",
        // Final review I4: the photo test's labels (queries.json holds the user's free text about their photos) never leave the phone.
        "file:photo-eval",
        // Always-on logging LGC2: files/logs (pca.log, pca.log.1) is a per-install diagnostic record: never backed up or transferred.
        "file:logs",
    ) + secretBaks + memoryCopies + slice3aFiles.map { "file:$it" } +
        // Final pass 10: the dev-mode trace staging folder under the app's EXTERNAL files dir (raw sensor traces, readable by adb): never backed up or transferred.
        "external:${com.rokid.context.canvas.trace.FencedFiles.STAGING_DIR}" + setupState.map { "sharedpref:$it.xml" }

    /**
     * B12 ruling: copies of the legacy key file found in files/ after a restore. Backup rules match a path exactly, or
     * a directory and everything under it: no wildcards. So the name itself is excluded (in case it is ever a file or a
     * directory), the two restored copies by their exact names (so the existing cloud backup can't put them back), and
     * SecretBakCleanup deletes any other app_config_secret.xml.bak.* at start-up.
     */
    private val secretBaks: Set<String> get() = setOf(
        "file:app_config_secret",
        "file:app_config_secret.xml.bak.1780057256",
        "file:app_config_secret.xml.bak.manual_reset_1780057830",
    )

    /**
     * BUG-R3-1 ruling: prefs that describe the set-up, the DB or this device. Restored onto a fresh install (no keys,
     * no DB) they would claim set-up is done (onboarding), point into the missing DB (DB mode flags, index tokens,
     * counters), carry the legacy provider config + pause stamp (app_config), or a glasses pairing (rokid_link and
     * the vendor SDK's rokid_bridge) or a recognizer probe made on another phone (voice_probe).
     */
    private val setupState: Set<String> get() = setOf(
        "onboarding", "app_config", "memory_graph", "index_maintenance", "pipeline_stats", "rokid_link", "rokid_bridge", "voice_probe",
        // Fix wave I2: the wearer's "I exited the glasses app" (device state of this pairing): never restored onto another phone.
        com.rokid.context.phone.hub.PrefsStandDown.PREFS,
        // Derived state: restored, they would say adoption / retirement already happened on an install that never did it.
        "frame_adoption", "space_retired_at", "image_read_attempts",
        // v0.9.9 final fixes: the hub restart ladder is device state (when this phone last tried to start its hub): never restored.
        "hub_restart",
        // v0.9.9 AutoStop: the manual stop, the auto stop and the wake ladder are device state: never restored onto another phone.
        com.rokid.context.phone.hub.PrefsAutoStopStore.PREFS,
        // Wave B: opt-in context sources, my-car addresses, the LiveFit digest, the watch epoch/seq: every source off after a restore.
        com.rokid.context.phone.context.ContextSourceSettings.PREFS,
        // Canvas slice 2: the Developer mode switch is a per-device debug flag (Shadow runs only while it is on): off after a restore.
        com.rokid.context.phone.canvas.DeveloperModeStore.PREFS,
        // Always-on logging LGC2: the pending log-wipe request is per-install state: never restored onto another install.
        com.rokid.context.phone.log.PrefsWipePending.PREFS,
        // AI call pace (10 / 12 / 15 s) paces this phone's own model calls (the call budget reads it): per-device, back to the default after a restore.
        com.rokid.context.phone.ui.settings.capture.AiCallPaceStore.PREFS,
        // 3d: the global window tolerance is a per-device compile default: back to 90 after a restore.
        com.rokid.context.phone.ui.settings.capture.WindowToleranceStore.PREFS,
        // v0.9.7 A: disabled catalogue signals and derived-signal definitions are per-device config tied to the canvas guards in the excluded DB: back to empty after a restore.
        com.rokid.context.phone.ui.settings.capture.SignalConfigStore.PREFS,
        // v0.9.7 B: the user's phone fusion thresholds are a per-device setting: back to the defaults after a restore.
        com.rokid.context.phone.ui.settings.capture.FusionSettingsStore.PREFS,
        // v0.9.8 D: the user's mic and display switches describe this phone's pairing with its glasses: back to all on after a restore.
        com.rokid.context.phone.ui.settings.capture.DeviceCapabilityStore.PREFS,
        com.rokid.context.phone.ui.settings.capture.CaptureTimingStore.PREFS,
        // Fix round 1: "Send matching photos with questions" is an opt-in privacy switch: off after a restore.
        com.rokid.context.orchestration.AskPhotoPolicy.PREFS,
    ) + slice3aPrefs

    /**
     * Canvas slice 3a (S57, S81): per-install state that must never be restored onto another install. The persisted deletion epoch (a restored counter or
     * install id would let another install's data pass its fence, or revoke a wipe), the plan and collection state, and the mark settings. The last three are
     * ruled on ahead of the tasks that create them, so the XML is final before any of that code can write a file.
     */
    private val slice3aPrefs: Set<String> get() = setOf(
        com.rokid.context.memory.db.DeletionEpochStore.PREFS, "canvas_plan_state", "canvas_collect", "mark_settings",
        // 3b WP2: the helper capability grants (py_exec, default off) are per-install consent: a restore never turns a grant on.
        com.rokid.context.phone.canvas.helper.HelperGrants.PREFS,
    )

    /** Slice 3a folders under `files/`: sealed sensor traces, pulled imports and their tmp files. Sensor and label data: never backed up or transferred. */
    private val slice3aFiles: Set<String> get() = setOf(
        com.rokid.context.canvas.trace.FencedFiles.TRACES_DIR, com.rokid.context.canvas.trace.FencedFiles.IMPORT_DIR, com.rokid.context.canvas.trace.FencedFiles.TMP_DIR,
    )

    /**
     * BUG-R3-1 ruling: plain user preferences, no ids into the DB and nothing about the set-up, so they are restored.
     * hub_settings also holds the Phase 7 pause-migration marker and a held pause id: with no pause row restored, the
     * held id is released at the first start (PausePipelineControl.watchHeld), and the legacy flag it migrated is in
     * app_config, which is not restored.
     */
    private val restorable = setOf(
        "hub_settings", "action_settings", "context_sources", "memory_settings", "retrieval_settings", "prompt_settings",
        "persona_settings", "voice_settings", "nudge_engine",
        // The scene-grouping watch list ("keys", "receipt" ...): a plain user list of words, no ids into the DB (Ruling: wave 2 Task 3).
        "watch_list",
        // Activity sort (Latest first / Oldest first / A-Z / Z-A) is a plain UI preference kept in hub_settings: restored.
        com.rokid.context.phone.ui.activity.PrefsActivitySort.PREFS,
    )

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

    @Test fun filesLogsExcludedFromBackupAndTransfer() {
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        assertEquals(3, sections.size)
        for (section in sections) assertTrue("${section.tagName}: file:logs", "file:logs" in excludes(section))
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

    /**
     * Every prefs file the phone APK's modules open is ruled on: excluded (a key, a DB marker or set-up state) or
     * restorable. A new prefs file fails here until it is added to one of the lists (and, if excluded, to the XML).
     */
    @Test fun everyPrefsFileIsRuledOn() {
        val roots = listOf("src/main", "../services").map(::File)
        val sources = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") && f.absoluteFile.invariantSeparatorsPath.let { p -> "/src/main/" in p && "/build/" !in p } } }
        val literal = Regex("""getSharedPreferences\(\s*"([^"]+)"""")
        val constant = Regex("""const val (?:PREFS|PREF|SECRET_PREF) = "([^"]+)"""")
        val created = Regex("""EncryptedSharedPreferences\.create\(\s*\w+,\s*"([^"]+)"""")
        val names = sources.flatMap { f -> val src = f.readText(); (literal.findAll(src) + constant.findAll(src) + created.findAll(src)).map { it.groupValues[1] } }.toSet()
        val keysAndMarkers = setOf("endpoint_keys", "db_key", "app_config_secret", "ai_seed", "legacy_import")
        assertTrue("found only ${names.size} prefs files: the scan is broken", names.size >= 15)
        // the phone module's own files are scanned too (a path filter once skipped them): these two live only in :phone
        assertTrue("the phone module's prefs are not scanned: $names", names.containsAll(setOf("pc_dev", "ai_call_pace")))
        val unruled = names - keysAndMarkers - setupState - restorable
        assertEquals("prefs files with no backup ruling", emptySet<String>(), unruled)
        assertTrue("a file is both restorable and excluded", (restorable intersect (setupState + keysAndMarkers)).isEmpty())
    }

    @Test fun secretBakFilesAreExcludedFromEveryBackupSection() {
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        assertEquals(3, sections.size)
        for (section in sections) {
            val ex = excludes(section)
            for (n in secretBaks + "sharedpref:app_config_secret.xml") assertTrue("${section.tagName}: $n", n in ex)
        }
    }

    /**
     * B12: no main code (any module, Kotlin or Java) writes a .bak copy of a secret. The only main source that names
     * ".bak" or "manual_reset" is the start-up cleanup, and it only lists and deletes.
     */
    @Test fun noCodePathWritesSecretBak() {
        val roots = listOf("src/main", "../services", "../glasses/src/main").map(::File)
        val sources = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && (f.name.endsWith(".kt") || f.name.endsWith(".java")) && "/build/" !in f.path && "/src/main/" in f.path } }
        assertTrue("scan is broken", sources.size > 50)
        val mentions = sources.filter { f -> val t = f.readText(); ".bak" in t || "manual_reset" in t }.map { it.name }
        assertEquals(listOf("SecretBakCleanup.kt"), mentions)
        val cleanup = sources.single { it.name == "SecretBakCleanup.kt" }.readText()
        for (w in listOf("writeText", "writeBytes", "copyTo", "renameTo", "FileOutputStream", "outputStream", "createNewFile", "Files.copy"))
            assertFalse("SecretBakCleanup uses $w", w in cleanup)
    }

    /** The Activity sort choice is a UI preference: it lives in a restorable file, never in an excluded one. */
    @Test fun activitySortIsRestorable() {
        val file = com.rokid.context.phone.ui.activity.PrefsActivitySort.PREFS
        assertEquals("hub_settings", file)
        assertTrue(file in restorable)
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        for (section in sections) assertTrue(section.tagName, "sharedpref:$file.xml" !in excludes(section))
    }

    /**
     * The On-device models page's "Allow mobile data" key lives in app_config, which BUG-R3-1 excludes: a restore
     * brings the switch back off (Wi-Fi only), the safe default, and never a stale "on" from another phone.
     */
    @Test fun theMobileDataSwitchIsInExcludedAppConfig() {
        assertEquals("app_config", com.rokid.context.phone.ui.settings.AppConfigMobileData.PREFS)
        assertEquals("local_models_allow_metered", com.rokid.context.phone.ui.settings.AppConfigMobileData.KEY)
        assertTrue("app_config" in setupState)
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        for (section in sections) assertTrue(section.tagName, "sharedpref:app_config.xml" in excludes(section))
    }

    private fun allSections() = listOf(parse("src/main/res/xml/backup_rules.xml")) +
        listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }

    /** S81: every slice 3a prefs file and folder is excluded in the legacy rules AND both data-extraction sections, under its exact name. */
    @Test fun slice3aPrefsAndFoldersAreExcludedEverywhere() {
        for (section in allSections()) {
            val ex = excludes(section)
            for (n in slice3aPrefs) assertTrue("${section.tagName}: $n.xml", "sharedpref:$n.xml" in ex)
            for (n in slice3aFiles) assertTrue("${section.tagName}: $n", "file:$n" in ex)
        }
        // None of them is restorable, and none is exported in a config export.
        assertTrue((restorable intersect slice3aPrefs).isEmpty())
        assertTrue(slice3aPrefs.none { it in com.rokid.context.orchestration.ConfigExport.PREFS_FILES })
    }

    /**
     * The new slice 3a tables are in `context.db`, which every section excludes (with its WAL and SHM), so they are never backed up or transferred. Reads
     * the exported schema: a table that moved to another database file would fail here.
     */
    @Test fun slice3aTablesLiveInTheExcludedDatabase() {
        assertEquals("context.db", com.rokid.context.memory.db.DatabaseFactory.NAME)
        val text = File("../services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/7.json").readText()
        val tables = Regex(""""tableName"\s*:\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        val slice3 = setOf("canvas_trace", "canvas_trace_event", "canvas_replay", "canvas_sweep", "canvas_label", "canvas_meta", "canvas_calibration", "canvas_group_state", "canvas_helper_job")
        assertTrue(tables.containsAll(slice3))
        for (section in allSections()) for (f in listOf("context.db", "context.db-wal", "context.db-shm")) assertTrue("${section.tagName}: $f", "database:$f" in excludes(section))
    }

    /** The folder names and the prefs name are code constants: pin them so a rename cannot silently leave the old name excluded. */
    @Test fun slice3aNamesMatchTheCode() {
        val fenced = File("../services/orchestration/src/main/java/com/rokid/context/canvas/trace/FencedFiles.kt").readText()
        for (n in slice3aFiles) assertTrue(n, fenced.contains("\"$n\""))
        assertTrue(File("../services/memory/src/main/java/com/rokid/context/memory/db/DeletionEpochStore.kt").readText().contains("const val PREFS = \"deletion_epoch\""))
    }
}
