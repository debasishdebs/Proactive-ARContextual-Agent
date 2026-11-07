package com.rokid.context.watch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Policy guard on our own manifest (unit tests run with the module directory as working directory). */
class WatchManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val wear = File("src/main/res/values/wear.xml").readText()

    @Test fun permissionsEqualTheAllowlistExactly() {
        val perms = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.permission.ACTIVITY_RECOGNITION", "android.permission.RECEIVE_BOOT_COMPLETED"), perms)
    }

    @Test fun noLocationBodySensorsHealthOrInternet() {
        for (p in listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "BODY_SENSORS", "READ_HEALTH_DATA", "health.READ", "INTERNET")) {
            assertTrue(p !in manifest, p)
        }
    }

    @Test fun passiveServiceIsBoundOnlyByHealthServices() = assertTrue(
        Regex("""\.motion\.MotionPassiveService"[^>]*healthservices\.permission\.PASSIVE_DATA_BINDING""").containsMatchIn(manifest),
    )

    @Test fun noProviderAndBackupIsOff() {
        assertTrue("<provider" !in manifest)
        assertTrue("android:allowBackup=\"false\"" in manifest)
    }

    @Test fun onlyTheMainActivityIsExportedAsLauncher() {
        assertEquals(1, Regex("<activity\\b").findAll(manifest).count())
        assertTrue("android.intent.category.LAUNCHER" in manifest)
    }

    @Test fun capabilityIsOnlyPcaWatch() {
        val items = Regex("<item>([^<]+)</item>").findAll(wear).map { it.groupValues[1] }.toList()
        assertEquals(listOf("pca_watch"), items)
    }

    @Test fun everyDomainIsExcludedFromEveryBackupSection() {
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
        assertTrue("android:fullBackupContent=\"@xml/backup_rules\"" in manifest)
        val domains = listOf("root", "file", "database", "sharedpref", "external")
        val extraction = File("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(extraction)?.groupValues?.get(1) ?: error(section)
            for (d in domains) assertTrue(Regex("""<exclude domain="$d" path="\."\s*/>""").containsMatchIn(body), "$section $d")
            assertTrue("<include" !in body, "$section has an include")
        }
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        for (d in domains) assertTrue(Regex("""<exclude domain="$d" path="\."\s*/>""").containsMatchIn(legacy), "backup_rules $d")
        assertTrue("<include" !in legacy)
    }
}
