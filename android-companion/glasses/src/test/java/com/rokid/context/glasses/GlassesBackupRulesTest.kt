package com.rokid.context.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * S2 ruling: nothing on the glasses is worth restoring. The outbound photo buffer holds camera frames, the pause store
 * and capture guard/scheduler prefs are session state, and the HUD settings cache is re-sent by the phone on link. So
 * backup is off, and because targetSdk >= 31 ignores allowBackup="false" for device-to-device transfer, the extraction
 * rules also exclude every domain in both sections (and the pre-31 rules do the same, belt and braces).
 */
class GlassesBackupRulesTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val everyDomain = listOf("root", "file", "database", "sharedpref", "external").map { "$it:." }.toSet()

    /** Slice 3a (S81), named on top of the blanket excludes: prefs of the capture plan and the mark queue, the IMU trace folder and its tmp folder. */
    private val slice3a = setOf("sharedpref:capture_plan.xml", "sharedpref:mark_queue.xml", "file:imu_trace", "file:imu_trace_tmp")

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File(path)).documentElement

    private fun excludes(section: Element): Set<String> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }.map { "${it.getAttribute("domain")}:${it.getAttribute("path")}" }.toSet()
    }

    @Test fun glassesBackupIsOffAndPointsAtBothRuleFiles() {
        val app = parse("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
    }

    @Test fun everySectionExcludesEveryDomainAndIncludesNothing() {
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { tag ->
                val s = parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(tag).item(0) as Element?
                assertTrue("missing <$tag>", s != null); s!!
            }
        for (section in sections) {
            assertEquals(section.tagName, everyDomain + slice3a, excludes(section))
            assertEquals(section.tagName, 0, section.getElementsByTagName("include").length)
        }
    }

    /** The stores the domain-wide excludes must cover all live in internal storage (filesDir or shared_prefs). */
    @Test fun theOutboundBufferAndPauseStoreLiveInFilesDir() {
        val src = File("src/main/java/com/rokid/context/glasses/GlassesRuntime.kt").readText()
        assertTrue(src.contains("File(app.filesDir, \"outbound\")"))
        assertTrue(src.contains("File(app.filesDir, \"pauses.preferences_pb\")"))
    }

    /** The blanket excludes stay in every section (the slice 3a names are an addition, never a replacement), so any file or pref not named is excluded too. */
    @Test fun theBlanketExcludesStayAndTheSlice3aNamesAreAdded() {
        val sections = listOf(parse("src/main/res/xml/backup_rules.xml")) +
            listOf("cloud-backup", "device-transfer").map { parse("src/main/res/xml/data_extraction_rules.xml").getElementsByTagName(it).item(0) as Element }
        for (section in sections) {
            val ex = excludes(section)
            assertTrue(section.tagName, ex.containsAll(everyDomain))
            assertTrue(section.tagName, ex.containsAll(slice3a))
        }
    }
}
